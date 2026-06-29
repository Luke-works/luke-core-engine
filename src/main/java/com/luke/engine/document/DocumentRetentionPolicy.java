package com.luke.engine.document;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides a document's retention horizon + S3 Object-Lock mode at authorize-time (DOC-5).
 *
 * <p>Retention is per-capability: SIGNATURES get a long, legal-grade window under <b>COMPLIANCE</b>
 * Object Lock (immutable even to the account root until expiry — US ESIGN/UETA evidence), everything
 * else gets the (usually 0 = none) default under <b>GOVERNANCE</b> (privileged users can still manage
 * it). A caller may pass an explicit {@code retainUntil} (e.g. SIGNATURES carrying its own
 * {@code retain-until}); it wins over the capability default.
 *
 * <p>The horizon + mode are returned to luke-file-proxy, which is the only tier holding S3 creds and so
 * the only place that can set Object Lock on the PUT. The local dev store no-ops on lock.
 */
@Component
public class DocumentRetentionPolicy {

    /** S3 Object Lock modes (string, mirrored on the proxy as {@code ObjectLockMode}). */
    public static final String MODE_COMPLIANCE = "COMPLIANCE";
    public static final String MODE_GOVERNANCE = "GOVERNANCE";

    private final long defaultDays;
    private final long signaturesDays;

    public DocumentRetentionPolicy(
            @Value("${luke.docstore.retention.default-days:0}") long defaultDays,
            @Value("${luke.docstore.retention.signatures-days:2555}") long signaturesDays) {
        this.defaultDays = defaultDays;
        this.signaturesDays = signaturesDays;
    }

    /** The retention horizon for a new doc: explicit value wins, else the per-capability default; null = none. */
    public LocalDateTime resolveRetainUntil(String capability, Long explicitMs) {
        if (explicitMs != null) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(explicitMs), ZoneOffset.UTC);
        }
        long days = days(capability);
        return days > 0 ? LocalDateTime.now().plusDays(days) : null;
    }

    /** Object-Lock mode for a retained doc (null when there's no retention). COMPLIANCE for SIGNATURES. */
    public String lockMode(String capability, LocalDateTime retainUntil) {
        if (retainUntil == null) {
            return null;
        }
        return isSignatures(capability) ? MODE_COMPLIANCE : MODE_GOVERNANCE;
    }

    private long days(String capability) {
        return isSignatures(capability) ? signaturesDays : defaultDays;
    }

    private static boolean isSignatures(String capability) {
        return "SIGNATURES".equalsIgnoreCase(capability);
    }
}
