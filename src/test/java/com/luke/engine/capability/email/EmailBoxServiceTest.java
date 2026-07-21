package com.luke.engine.capability.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * H2-backed integration test of {@link EmailBoxService} — the Postmark HTTP clients are mocked
 * (no network), but the real repositories exercise persistence, address building, uniqueness,
 * and inbound routing resolution.
 */
@SpringBootTest
class EmailBoxServiceTest {

    private static final String TENANT = "EBX-TEST";

    @Autowired private EmailBoxService boxService;
    @Autowired private EmailServerRepository servers;
    @Autowired private EmailBoxRepository boxes;

    @MockBean private EmailServerService serverService;
    @MockBean private PostmarkClient postmark;
    @MockBean private PostmarkAccountClient accountClient;

    @BeforeEach
    void seed() {
        cleanup();
        EmailServer s = new EmailServer(); // id is @GeneratedValue — let Hibernate assign it
        s.setTenantId(TENANT);
        s.setCompanySlug("acme");
        s.setSenderDomain("acme.lukeflow.com");
        s.setDefaultFrom("no-reply@acme.lukeflow.com");
        s.setPostmarkServerId(123L);
        s.setMessageStream("outbound");
        s.setStatus("ACTIVE");
        servers.save(s);
    }

    @AfterEach
    void cleanup() {
        boxes.findByTenantIdOrderByCreatedAtAsc(TENANT).forEach(b -> boxes.deleteById(b.getId()));
        servers.findByTenantId(TENANT).ifPresent(servers::delete);
    }

    @Test
    void outboundBoxCreatesAPostmarkStreamAndPersists() {
        when(serverService.resolveServerToken(TENANT)).thenReturn("server-token");
        when(postmark.createMessageStream(any(), any(), any(), any()))
                .thenReturn(new PostmarkClient.StreamResult(true, "support", null));

        var res = boxService.register(TENANT,
                new EmailBoxService.RegisterRequest("OUTBOUND", "Support", "Support desk", null, null));

        assertThat(res.box().getAddress()).isEqualTo("support@acme.lukeflow.com");
        assertThat(res.box().getDirection()).isEqualTo("OUTBOUND");
        assertThat(res.box().getPostmarkStreamId()).isEqualTo("support");
        assertThat(res.box().isWorkflowTrigger()).isFalse();
        assertThat(boxes.findByTenantIdOrderByCreatedAtAsc(TENANT)).hasSize(1);
    }

    @Test
    void inboundBoxSetsTokenAndIsRoutableByAddressAndMailboxHash() {
        var res = boxService.register(TENANT,
                new EmailBoxService.RegisterRequest("INBOUND", "sales", null, null, true));

        assertThat(res.box().getDirection()).isEqualTo("INBOUND");
        assertThat(res.box().isWorkflowTrigger()).isTrue();
        assertThat(res.box().getRoutingKey()).isEqualTo("sales");
        // token generated on first inbound box; no public base URL in test → a warning is returned
        assertThat(servers.findByTenantId(TENANT).orElseThrow().getInboundHookToken()).isNotBlank();
        assertThat(res.warning()).isNotNull();

        assertThat(boxService.resolveInbound(TENANT, "sales@acme.lukeflow.com", null)).isPresent();
        assertThat(boxService.resolveInbound(TENANT, "nope@acme.lukeflow.com", "sales")).isPresent();
        assertThat(boxService.resolveInbound(TENANT, "nobody@acme.lukeflow.com", "other")).isEmpty();
    }

    @Test
    void duplicateBoxIsRejected() {
        boxService.register(TENANT, new EmailBoxService.RegisterRequest("INBOUND", "dup", null, null, true));
        assertThatThrownBy(() ->
                boxService.register(TENANT, new EmailBoxService.RegisterRequest("INBOUND", "dup", null, null, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void badDirectionIsRejected() {
        assertThatThrownBy(() ->
                boxService.register(TENANT, new EmailBoxService.RegisterRequest("SIDEWAYS", "x", null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void registeringBeforeEmailSetupConflicts() {
        servers.findByTenantId(TENANT).ifPresent(servers::delete);
        assertThatThrownBy(() ->
                boxService.register(TENANT, new EmailBoxService.RegisterRequest("OUTBOUND", "x", null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Set up email");
    }
}
