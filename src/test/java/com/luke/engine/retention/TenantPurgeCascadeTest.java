package com.luke.engine.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.luke.engine.capability.access.CapabilityAdminController;
import com.luke.engine.capability.email.EmailMessage;
import com.luke.engine.capability.email.EmailMessageRepository;
import com.luke.engine.capability.email.EmailVerification;
import com.luke.engine.capability.email.EmailVerificationRepository;
import com.luke.engine.capability.form.FormAuditEvent;
import com.luke.engine.capability.form.FormAuditEventRepository;
import com.luke.engine.capability.form.FormInstance;
import com.luke.engine.capability.form.FormInstanceRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * #53 (AC-4): deleting a tenant must leave no personal data — {@code purgeTenant} now cascades the
 * PII/audit trails (email sends, form submissions + lifecycle events, OTP challenges), not just
 * grants and subscriptions. Uses a unique tenant so the tenant-scoped deletes touch only its rows.
 */
@SpringBootTest
class TenantPurgeCascadeTest {

    @Autowired private CapabilityAdminController admin;
    @Autowired private EmailMessageRepository emails;
    @Autowired private FormInstanceRepository instances;
    @Autowired private FormAuditEventRepository audit;
    @Autowired private EmailVerificationRepository verifications;

    @Test
    void purgeTenantRemovesAllPiiTrailsForThatTenant() {
        String tenant = "PURGE-" + UUID.randomUUID();

        EmailMessage em = new EmailMessage();
        em.setTenantId(tenant);
        em.setFromAddress("from@example.com");
        em.setToAddress("to@example.com");
        em = emails.save(em);

        FormInstance fi = new FormInstance();
        fi.setTenantId(tenant);
        fi.setToken("tok-" + UUID.randomUUID());
        fi.setDefinitionCode("PURGE-FORM");
        fi.setData(Map.of("answer", "42"));
        fi = instances.save(fi);

        FormAuditEvent fa = new FormAuditEvent();
        fa.setTenantId(tenant);
        fa.setFormId("form-" + UUID.randomUUID());
        fa.setAction("published");
        fa = audit.save(fa);

        EmailVerification ev = new EmailVerification();
        ev.setTenantId(tenant);
        ev.setOrgName("Acme");
        ev.setEmail("jane@acme.com");
        ev.setDomain("acme.com");
        ev.setStatus(EmailVerification.VERIFIED);
        ev.setCodeHash("hash");
        ev.setCodeSalt("salt");
        ev.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        ev = verifications.save(ev);

        admin.purgeTenant(tenant);

        assertThat(emails.existsById(em.getId())).as("email sends purged").isFalse();
        assertThat(instances.existsById(fi.getId())).as("form submissions purged").isFalse();
        assertThat(audit.existsById(fa.getId())).as("form audit purged").isFalse();
        assertThat(verifications.existsById(ev.getId())).as("OTP challenges purged").isFalse();
    }
}
