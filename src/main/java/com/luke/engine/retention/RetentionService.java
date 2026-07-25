package com.luke.engine.retention;

import com.luke.engine.capability.email.EmailMessageRepository;
import com.luke.engine.capability.email.EmailVerification;
import com.luke.engine.capability.email.EmailVerificationRepository;
import com.luke.engine.capability.form.FormAuditEventRepository;
import com.luke.engine.capability.form.FormInstanceRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the data-retention policy (#53) to the PII/audit trails that otherwise grow forever. Each
 * method acts on rows older than a {@code cutoff} and returns how many rows were (or, in dry-run,
 * WOULD be) affected — the scheduling, config and dry-run gating live in {@link RetentionPurgeJob}.
 *
 * <p>Two dispositions: <b>delete</b> pure logs (email sends, form lifecycle events) and
 * <b>anonymize</b> where a non-PII audit row should persist (form-instance payloads nulled;
 * terminal OTP challenges keep their status but lose the org email/domain). Every operation is a
 * single bounded, idempotent bulk statement (a second pass past the same cutoff is a no-op).
 */
@Service
public class RetentionService {

    /** Placeholder written over redacted PII columns (they are NOT NULL, so we can't null them). */
    static final String REDACTED = "[redacted]";

    /** OTP challenges are eligible for PII redaction only once terminal (the proof is consumed). */
    static final List<String> TERMINAL_VERIFICATION_STATUSES =
            List.of(EmailVerification.VERIFIED, EmailVerification.EXPIRED, EmailVerification.FAILED);

    private final EmailMessageRepository emailMessages;
    private final FormInstanceRepository formInstances;
    private final FormAuditEventRepository formAuditEvents;
    private final EmailVerificationRepository emailVerifications;

    public RetentionService(EmailMessageRepository emailMessages, FormInstanceRepository formInstances,
                            FormAuditEventRepository formAuditEvents, EmailVerificationRepository emailVerifications) {
        this.emailMessages = emailMessages;
        this.formInstances = formInstances;
        this.formAuditEvents = formAuditEvents;
        this.emailVerifications = emailVerifications;
    }

    /** Delete email send-logs (recipient PII) created before {@code cutoff}. */
    @Transactional
    public long purgeEmailMessages(LocalDateTime cutoff, boolean dryRun) {
        long n = emailMessages.countByCreatedAtBefore(cutoff);
        if (n > 0 && !dryRun) {
            emailMessages.deleteCreatedBefore(cutoff);
        }
        return n;
    }

    /** Anonymize form-instance payloads (data/prefill/recipient) created before {@code cutoff},
     *  keeping the non-PII lifecycle row. */
    @Transactional
    public long anonymizeFormInstances(LocalDateTime cutoff, boolean dryRun) {
        long n = formInstances.countAnonymizableBefore(cutoff);
        if (n > 0 && !dryRun) {
            formInstances.anonymizeCreatedBefore(cutoff);
        }
        return n;
    }

    /** Delete form lifecycle audit events recorded before {@code cutoff}. */
    @Transactional
    public long purgeFormAuditEvents(LocalDateTime cutoff, boolean dryRun) {
        long n = formAuditEvents.countByAtBefore(cutoff);
        if (n > 0 && !dryRun) {
            formAuditEvents.deleteCreatedBefore(cutoff);
        }
        return n;
    }

    /** Redact the org email/domain/name on TERMINAL OTP challenges created before {@code cutoff}. */
    @Transactional
    public long redactVerifications(LocalDateTime cutoff, boolean dryRun) {
        long n = emailVerifications.countRedactableBefore(cutoff, TERMINAL_VERIFICATION_STATUSES, REDACTED);
        if (n > 0 && !dryRun) {
            emailVerifications.redactTerminalBefore(cutoff, TERMINAL_VERIFICATION_STATUSES, REDACTED);
        }
        return n;
    }
}
