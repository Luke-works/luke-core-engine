package com.luke.engine.capability.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.secrets.SecretStore;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link EmailServerService#resolveSendContext}: the sender-ownership
 * guarantee. When a base domain is configured, a tenant may only send from its own
 * provisioned domain; the per-tenant Postmark token comes from the secret store.
 */
class EmailServerServiceTest {

    private static final String TENANT = "TEN-ACM-01JAN26";
    private static final String TOKEN_KEY = "postmark.server-token";

    private EmailServerRepository servers;
    private SecretStore secretStore;
    private EmailServerService service;

    @BeforeEach
    void setUp() {
        servers = mock(EmailServerRepository.class);
        SecretStore store = mock(SecretStore.class);
        this.secretStore = store;
        service = new EmailServerService(servers, mock(PostmarkAccountClient.class), store);

        // @Value fields aren't injected without Spring — set the ones under test.
        ReflectionTestUtils.setField(service, "defaultLocalPart", "no-reply");
        ReflectionTestUtils.setField(service, "fallbackServerToken", "fallback-token");
        ReflectionTestUtils.setField(service, "fallbackFrom", "noreply@lukeflow.com");
        ReflectionTestUtils.setField(service, "fallbackStream", "outbound");
    }

    private void enforce() {
        ReflectionTestUtils.setField(service, "baseDomain", "lukeflow.com");
    }

    private EmailServer acmeServer() {
        EmailServer s = new EmailServer();
        s.setTenantId(TENANT);
        s.setCompanySlug("acme");
        s.setSenderDomain("acme.lukeflow.com");
        s.setDefaultFrom("no-reply@acme.lukeflow.com");
        s.setMessageStream("outbound");
        return s;
    }

    @Test
    void enforcedWithoutServerIsConflict() {
        enforce();
        when(servers.findByTenantId(TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveSendContext(TENANT, "x@acme.lukeflow.com"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void enforcedRejectsForeignSenderDomain() {
        enforce();
        when(servers.findByTenantId(TENANT)).thenReturn(Optional.of(acmeServer()));
        when(secretStore.get(TENANT, TOKEN_KEY)).thenReturn(Optional.of("acme-token"));

        assertThatThrownBy(() -> service.resolveSendContext(TENANT, "ceo@evil.com"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void enforcedAcceptsOwnDomainAndUsesTenantToken() {
        enforce();
        when(servers.findByTenantId(TENANT)).thenReturn(Optional.of(acmeServer()));
        when(secretStore.get(TENANT, TOKEN_KEY)).thenReturn(Optional.of("acme-token"));

        EmailServerService.SendContext ctx = service.resolveSendContext(TENANT, "hi@acme.lukeflow.com");

        assertThat(ctx.serverToken()).isEqualTo("acme-token");
        assertThat(ctx.from()).isEqualTo("hi@acme.lukeflow.com");
        assertThat(ctx.messageStream()).isEqualTo("outbound");
    }

    @Test
    void blankFromFallsBackToCompanyDefault() {
        enforce();
        when(servers.findByTenantId(TENANT)).thenReturn(Optional.of(acmeServer()));
        when(secretStore.get(TENANT, TOKEN_KEY)).thenReturn(Optional.of("acme-token"));

        EmailServerService.SendContext ctx = service.resolveSendContext(TENANT, null);
        assertThat(ctx.from()).isEqualTo("no-reply@acme.lukeflow.com");
    }

    @Test
    void provisionedButMissingTokenIsUnavailable() {
        enforce();
        when(servers.findByTenantId(TENANT)).thenReturn(Optional.of(acmeServer()));
        when(secretStore.get(TENANT, TOKEN_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveSendContext(TENANT, "hi@acme.lukeflow.com"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void notEnforcedUsesFallbackTokenAndFrom() {
        // No base domain → enforcement off (dev / OTP-before-provisioning).
        when(servers.findByTenantId(TENANT)).thenReturn(Optional.empty());

        EmailServerService.SendContext ctx = service.resolveSendContext(TENANT, null);

        assertThat(ctx.serverToken()).isEqualTo("fallback-token");
        assertThat(ctx.from()).isEqualTo("noreply@lukeflow.com");
    }
}
