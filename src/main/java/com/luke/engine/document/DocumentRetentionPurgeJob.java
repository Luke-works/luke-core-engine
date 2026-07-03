package com.luke.engine.document;

import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention purge (DOC-5): once retention has lapsed, a document becomes eligible for purge. This job
 * finds rows whose {@code retainUntil} is in the PAST and that are not already DELETED, and logically
 * purges them (status=DELETED + deletedAt) — the {@code luke_document} row stays the system of record.
 *
 * <p><b>Why no S3 delete here:</b> core holds no S3 credentials by design (the bytes tier is
 * luke-file-proxy). Physical byte reclamation is handled by the bucket's provisioned lifecycle rules
 * (which expire noncurrent/old versions) — and COMPLIANCE-locked objects (SIGNATURES) physically cannot
 * be deleted before expiry regardless. User-initiated deletes still drop the live object via the proxy.
 *
 * <p>Disabled by default (dev/qa). Enable in prod with {@code LUKE_DOC_PURGE_ENABLED=true}; keep
 * {@code LUKE_DOC_PURGE_DRY_RUN=true} first to log what WOULD be purged without mutating anything.
 */
@Component
public class DocumentRetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentRetentionPurgeJob.class);
    private static final int BATCH = 500;

    private final DocumentRepository repo;

    @Value("${luke.docstore.purge.enabled:false}")
    private boolean enabled;

    @Value("${luke.docstore.purge.dry-run:true}")
    private boolean dryRun;

    public DocumentRetentionPurgeJob(DocumentRepository repo) {
        this.repo = repo;
    }

    @Scheduled(
            fixedDelayString = "${luke.docstore.purge.interval-ms:86400000}",
            initialDelayString = "${luke.docstore.purge.interval-ms:86400000}")
    @Transactional
    public void purge() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // retainUntil < now AND status != DELETED: retention has lapsed, not yet purged.
        List<Document> expired = repo.findByRetainUntilBeforeAndStatusNot(now, Document.STATUS_DELETED);
        if (expired.isEmpty()) {
            return;
        }
        if (expired.size() > BATCH) {
            expired = expired.subList(0, BATCH);   // cap one pass; the next tick takes the rest
        }
        if (dryRun) {
            log.info("Retention purge (DRY-RUN): {} document(s) past retention would be purged", expired.size());
            return;
        }
        for (Document d : expired) {
            d.setStatus(Document.STATUS_DELETED);
            d.setDeletedAt(now);
        }
        repo.saveAll(expired);
        log.info("Retention purge: marked {} document(s) DELETED (bytes reclaimed by S3 lifecycle)", expired.size());
    }
}
