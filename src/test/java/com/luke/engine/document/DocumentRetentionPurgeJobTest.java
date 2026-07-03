package com.luke.engine.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * DOC-5 verification: the retention purge marks only EXPIRED, not-already-deleted rows DELETED; never
 * touches docs still under retention; and a dry-run mutates nothing.
 */
@DataJpaTest
class DocumentRetentionPurgeJobTest {

    @Autowired DocumentRepository repo;

    private Document doc(String id, LocalDateTime retainUntil, String status) {
        Document d = new Document();
        d.setId(id);
        d.setTenantId("t1");
        d.setProcessRef("proc-A");
        d.setKind(Document.KIND_GENERIC);
        d.setCapability("FORMS");
        d.setStorageKey("proc-A/" + id + "-f.pdf");
        d.setFilename("f.pdf");
        d.setContentType("application/pdf");
        d.setStatus(status);
        d.setRetainUntil(retainUntil);
        return d;
    }

    @Test
    void purgesExpiredRowsOnlyWhenNotDryRun() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        LocalDateTime future = LocalDateTime.now().plusDays(1);
        repo.save(doc("expired", past, Document.STATUS_READY));
        repo.save(doc("retained", future, Document.STATUS_READY));
        repo.save(doc("already", past, Document.STATUS_DELETED));

        DocumentRetentionPurgeJob job = new DocumentRetentionPurgeJob(repo);
        ReflectionTestUtils.setField(job, "enabled", true);

        // dry-run: nothing changes
        ReflectionTestUtils.setField(job, "dryRun", true);
        job.purge();
        assertThat(repo.findById("expired").orElseThrow().getStatus()).isEqualTo(Document.STATUS_READY);

        // real run: only the expired, non-deleted row flips to DELETED
        ReflectionTestUtils.setField(job, "dryRun", false);
        job.purge();
        assertThat(repo.findById("expired").orElseThrow().getStatus()).isEqualTo(Document.STATUS_DELETED);
        assertThat(repo.findById("expired").orElseThrow().getDeletedAt()).isNotNull();
        assertThat(repo.findById("retained").orElseThrow().getStatus()).isEqualTo(Document.STATUS_READY);
    }

    @Test
    void disabledByDefaultDoesNothing() {
        repo.save(doc("expired", LocalDateTime.now().minusDays(1), Document.STATUS_READY));
        DocumentRetentionPurgeJob job = new DocumentRetentionPurgeJob(repo);
        // enabled defaults false (field not set) → no-op
        job.purge();
        assertThat(repo.findById("expired").orElseThrow().getStatus()).isEqualTo(Document.STATUS_READY);
    }
}
