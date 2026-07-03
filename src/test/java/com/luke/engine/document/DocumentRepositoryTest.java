package com.luke.engine.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * DOC-2 verification: the Document entity maps and every tenant-scoped derived finder resolves
 * against it (Spring Data parses method names at context start — a typo fails fast here). Uses the
 * default sliced H2 (ddl-auto creates the schema from the entity, matching V7__document_table.sql).
 */
@DataJpaTest
class DocumentRepositoryTest {

    @Autowired
    DocumentRepository repo;

    private Document doc(String tenant, String processRef, String taskId, String kind, String cap, String owner) {
        Document d = new Document();
        d.setTenantId(tenant);
        d.setProcessRef(processRef);
        d.setTaskId(taskId);
        d.setKind(kind);
        d.setCapability(cap);
        d.setOwnerEntityId(owner);
        d.setStorageKey(tenant + "/" + processRef + "/k");
        d.setFilename("f.pdf");
        d.setContentType("application/pdf");
        d.setStatus(Document.STATUS_PENDING);
        return d;
    }

    @Test
    void persistsAndQueriesByProcessTaskAndOwner() {
        Document caseLevel = repo.save(doc("t1", "proc-A", null, Document.KIND_FORM_ATTACHMENT, "FORMS", "form-9"));
        Document taskLevel = repo.save(doc("t1", "proc-A", "task-7", Document.KIND_GENERIC, "FORMS", null));
        repo.save(doc("t2", "proc-A", null, Document.KIND_FORM_ATTACHMENT, "FORMS", "form-9")); // other tenant

        assertThat(caseLevel.getId()).isNotBlank();           // UUID assigned
        assertThat(caseLevel.getCreatedAt()).isNotNull();     // @PrePersist set it

        // whole case file for t1/proc-A = both docs, not t2's
        assertThat(repo.findByTenantIdAndProcessRefOrderByCreatedAtDesc("t1", "proc-A")).hasSize(2);
        // one task's attachments
        assertThat(repo.findByTenantIdAndTaskIdOrderByCreatedAtDesc("t1", "task-7"))
                .extracting(Document::getId).containsExactly(taskLevel.getId());
        // by capability owner
        assertThat(repo.findByTenantIdAndCapabilityAndOwnerEntityIdOrderByCreatedAtDesc("t1", "FORMS", "form-9"))
                .extracting(Document::getId).containsExactly(caseLevel.getId());
        // tenant isolation: t1 finder never returns t2's row
        assertThat(repo.findByIdAndTenantId(caseLevel.getId(), "t2")).isEmpty();
        assertThat(repo.findByIdAndTenantId(caseLevel.getId(), "t1")).isPresent();
    }

    @Test
    void retentionPurgeFinderMatchesExpiredNonDeleted() {
        Document d = doc("t1", "proc-R", null, Document.KIND_SIGNATURE_ATTACHMENT, "SIGNATURES", "sr-1");
        d.setStatus(Document.STATUS_READY);
        d.setRetainUntil(LocalDateTime.now().minusDays(1));
        repo.save(d);

        List<Document> expired = repo.findByRetainUntilBeforeAndStatusNot(LocalDateTime.now(), Document.STATUS_DELETED);
        assertThat(expired).extracting(Document::getId).contains(d.getId());
    }
}
