package com.luke.engine.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * #37 — {@link AdminAuditService} is the single write path for the admin audit trail. It must persist the
 * who/what/target/tenant faithfully, stamp the request source (IP/method/path) without call sites
 * threading a request through, and — critically — NEVER let an audit-store failure break the
 * privileged action it records (default-lenient).
 */
@SpringBootTest
class AuditServiceTest {

    @Autowired
    private AdminAuditService audit;

    @Autowired
    private AuditEventRepository repository;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void recordPersistsActorActionTargetTenantAndDetail() {
        String tenant = "AUD-SVC-" + UUID.randomUUID();
        audit.record("capability.grant", "capability", "FORMS", tenant, "actor-1", true,
                Map.of("userId", "u-9", "level", "read-write"));

        Page<AuditEvent> page = repository.findByTenantIdOrderByCreatedAtDesc(tenant, PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(1);
        AuditEvent e = page.getContent().get(0);
        assertThat(e.getAction()).isEqualTo("capability.grant");
        assertThat(e.getTargetType()).isEqualTo("capability");
        assertThat(e.getTargetId()).isEqualTo("FORMS");
        assertThat(e.getActorId()).isEqualTo("actor-1");
        assertThat(e.isActorOperator()).isTrue();
        assertThat(e.getTenantId()).isEqualTo(tenant);
        assertThat(e.getId()).isNotBlank();
        assertThat(e.getCreatedAt()).isNotNull();
        assertThat(e.getDetail()).contains("read-write").contains("u-9");
    }

    @Test
    void recordStampsSourceFromTheInFlightRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/org/users");
        req.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1"); // left-most PUBLIC hop wins
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        String tenant = "AUD-SRC-" + UUID.randomUUID();
        audit.record("user.create", "user", "new-user", tenant, "actor-2", false);

        AuditEvent e = repository.findByTenantIdOrderByCreatedAtDesc(tenant, PageRequest.of(0, 10))
                .getContent().get(0);
        assertThat(e.getSourceIp()).isEqualTo("203.0.113.7");
        assertThat(e.getRequestMethod()).isEqualTo("POST");
        assertThat(e.getRequestPath()).isEqualTo("/api/org/users");
    }

    @Test
    void recordNeverThrowsWhenTheStoreFails() {
        // A broken/absent audit store must degrade to log-only, not 500 the admin action it records.
        AuditEventRepository broken = mock(AuditEventRepository.class);
        when(broken.save(any())).thenThrow(new RuntimeException("audit table unavailable"));
        AdminAuditService failSoft = new AdminAuditService(broken);

        assertThatCode(() -> failSoft.record("tenant.delete", "tenant", "T-1", "T-1", "op", true))
                .doesNotThrowAnyException();
    }
}
