package com.luke.engine.retention;

import java.time.LocalDateTime;
import java.util.function.LongUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled data-retention purge (#53) — enforces the per-data-class retention windows by deleting
 * or anonymizing rows past their cutoff via {@link RetentionService}. Mirrors
 * {@code DocumentRetentionPurgeJob}.
 *
 * <p><b>DEFAULT-LENIENT — off unless explicitly configured.</b> The master switch
 * {@code luke.retention.enabled} defaults to {@code false} (dev/qa and prod do nothing), and even
 * when enabled each data class is skipped until its window ({@code *-days}) is set to a positive
 * value. {@code luke.retention.dry-run} defaults to {@code true}, so the first enablement only LOGS
 * what WOULD be purged — an operator confirms the counts before flipping {@code dry-run=false}. This
 * makes it impossible to silently delete data by merely deploying this change.
 *
 * <p>Enable in prod with, e.g. {@code LUKE_RETENTION_ENABLED=true},
 * {@code LUKE_RETENTION_EMAIL_MESSAGES_DAYS=365}, first with {@code LUKE_RETENTION_DRY_RUN=true}.
 */
@Component
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);

    private final RetentionService retention;

    @Value("${luke.retention.enabled:false}")
    private boolean enabled;

    @Value("${luke.retention.dry-run:true}")
    private boolean dryRun;

    @Value("${luke.retention.email-messages-days:0}")
    private int emailMessagesDays;

    @Value("${luke.retention.form-instances-days:0}")
    private int formInstancesDays;

    @Value("${luke.retention.form-audit-days:0}")
    private int formAuditDays;

    @Value("${luke.retention.email-verifications-days:0}")
    private int emailVerificationsDays;

    public RetentionPurgeJob(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(
            fixedDelayString = "${luke.retention.interval-ms:86400000}",
            initialDelayString = "${luke.retention.interval-ms:86400000}")
    public void run() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String mode = dryRun ? "DRY-RUN" : "applied";
        act("email messages deleted", emailMessagesDays, mode,
                days -> retention.purgeEmailMessages(now.minusDays(days), dryRun));
        act("form instances anonymized", formInstancesDays, mode,
                days -> retention.anonymizeFormInstances(now.minusDays(days), dryRun));
        act("form audit events deleted", formAuditDays, mode,
                days -> retention.purgeFormAuditEvents(now.minusDays(days), dryRun));
        act("email verifications redacted", emailVerificationsDays, mode,
                days -> retention.redactVerifications(now.minusDays(days), dryRun));
    }

    /** Run one data class's retention op if its window is set (>0), logging any non-zero result. */
    private void act(String label, int days, String mode, LongUnaryOperator op) {
        if (days <= 0) {
            return; // window unset → this data class is not retained/purged
        }
        long n = op.applyAsLong(days);
        if (n > 0) {
            log.info("Retention [{}]: {} {} (window {}d)", mode, n, label, days);
        }
    }
}
