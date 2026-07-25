package com.luke.engine.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.luke.engine.capability.email.EmailMessage;
import com.luke.engine.capability.email.EmailMessageRepository;
import com.luke.engine.capability.email.EmailVerification;
import com.luke.engine.capability.email.EmailVerificationRepository;
import com.luke.engine.capability.form.FormAuditEvent;
import com.luke.engine.capability.form.FormAuditEventRepository;
import com.luke.engine.capability.form.FormInstance;
import com.luke.engine.capability.form.FormInstanceRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * #53: the retention policy operations. Rows are back-dated (reflection) to 100 days old and the
 * cutoff is 50 days, so ONLY these seeded rows fall past the window — the global bulk ops leave
 * every other test's freshly-created rows untouched. Covers delete, anonymize and redact, the
 * dry-run no-op, and the recent-row / non-terminal exclusions.
 */
@SpringBootTest
class RetentionServiceTest {

    private static final LocalDateTime OLD = LocalDateTime.now().minusDays(100);
    private static final LocalDateTime CUTOFF = LocalDateTime.now().minusDays(50); // OLD < CUTOFF < now

    @Autowired private RetentionService retention;
    @Autowired private EmailMessageRepository emails;
    @Autowired private FormInstanceRepository instances;
    @Autowired private FormAuditEventRepository audit;
    @Autowired private EmailVerificationRepository verifications;

    private static void backdate(Object entity, String field, LocalDateTime when) {
        try {
            Field f = entity.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(entity, when);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String uid(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    @Test
    void emailMessagesAreDeletedPastTheCutoffButRecentOnesKept() {
        EmailMessage old = new EmailMessage();
        old.setTenantId(uid("RET"));
        old.setFromAddress("from@example.com");
        old.setToAddress("recipient@example.com");
        backdate(old, "createdAt", OLD);
        old = emails.save(old);

        EmailMessage recent = new EmailMessage();
        recent.setTenantId(uid("RET"));
        recent.setFromAddress("from@example.com");
        recent.setToAddress("recipient@example.com");
        recent = emails.save(recent); // createdAt defaults to now

        // Dry-run counts but does not delete.
        assertThat(retention.purgeEmailMessages(CUTOFF, true)).isGreaterThanOrEqualTo(1);
        assertThat(emails.existsById(old.getId())).isTrue();

        assertThat(retention.purgeEmailMessages(CUTOFF, false)).isGreaterThanOrEqualTo(1);
        assertThat(emails.existsById(old.getId())).as("old deleted").isFalse();
        assertThat(emails.existsById(recent.getId())).as("recent kept").isTrue();
    }

    @Test
    void formInstancePayloadIsAnonymizedButLifecycleRowKept() {
        FormInstance old = new FormInstance();
        old.setTenantId(uid("RET"));
        old.setToken(uid("tok"));
        old.setDefinitionCode("RET-FORM");
        old.setData(Map.of("ssn", "123-45-6789"));
        old.setRecipient(Map.of("email", "submitter@example.com"));
        old.setPrefill(Map.of("name", "Jane"));
        backdate(old, "createdAt", OLD);
        old = instances.save(old);
        String id = old.getId();

        assertThat(retention.anonymizeFormInstances(CUTOFF, false)).isGreaterThanOrEqualTo(1);

        FormInstance after = instances.findById(id).orElseThrow();
        // The DB columns are set to NULL; JsonMapConverter maps a null column back to an empty map
        // on read, so the PII is gone either way — assert null-or-empty.
        assertThat(after.getData()).as("PII payload cleared").isNullOrEmpty();
        assertThat(after.getRecipient()).isNullOrEmpty();
        assertThat(after.getPrefill()).isNullOrEmpty();
        assertThat(after.getState()).as("lifecycle metadata kept").isNotNull();
        assertThat(after.getDefinitionCode()).isEqualTo("RET-FORM");

        // Idempotent: the now-anonymized row (null columns) is no longer eligible.
        assertThat(retention.anonymizeFormInstances(CUTOFF, false)).isZero();
        assertThat(instances.findById(id).orElseThrow().getData()).isNullOrEmpty();
    }

    @Test
    void formAuditEventsAreDeletedPastTheCutoff() {
        FormAuditEvent old = new FormAuditEvent();
        old.setFormId(uid("form"));
        old.setTenantId(uid("RET"));
        old.setAction("published");
        backdate(old, "at", OLD);
        old = audit.save(old);

        assertThat(retention.purgeFormAuditEvents(CUTOFF, false)).isGreaterThanOrEqualTo(1);
        assertThat(audit.existsById(old.getId())).isFalse();
    }

    @Test
    void terminalVerificationsAreRedactedButPendingAndRecentAreNot() {
        EmailVerification terminal = verification("VERIFIED", "jane@acme.com", "acme.com", "Acme");
        backdate(terminal, "createdAt", OLD);
        terminal = verifications.save(terminal);

        EmailVerification pending = verification(EmailVerification.PENDING, "pending@acme.com", "acme.com", "Acme");
        backdate(pending, "createdAt", OLD);
        pending = verifications.save(pending);

        assertThat(retention.redactVerifications(CUTOFF, false)).isGreaterThanOrEqualTo(1);

        EmailVerification t = verifications.findById(terminal.getId()).orElseThrow();
        assertThat(t.getEmail()).isEqualTo("[redacted]");
        assertThat(t.getDomain()).isEqualTo("[redacted]");
        assertThat(t.getStatus()).as("status kept for audit").isEqualTo("VERIFIED");

        EmailVerification p = verifications.findById(pending.getId()).orElseThrow();
        assertThat(p.getEmail()).as("non-terminal not redacted").isEqualTo("pending@acme.com");
    }

    private EmailVerification verification(String status, String email, String domain, String org) {
        EmailVerification v = new EmailVerification();
        v.setTenantId(uid("RET"));
        v.setOrgName(org);
        v.setEmail(email);
        v.setDomain(domain);
        v.setStatus(status);
        v.setCodeHash("hash");
        v.setCodeSalt("salt");
        v.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return v;
    }
}
