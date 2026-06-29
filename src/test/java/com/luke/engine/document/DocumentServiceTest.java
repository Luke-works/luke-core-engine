package com.luke.engine.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.access.CapabilityAccessService;
import com.luke.engine.document.DocumentDtos.AuthorizeRequest;
import com.luke.engine.document.DocumentDtos.AuthorizeResponse;
import com.luke.engine.document.DocumentDtos.FinalizeRequest;
import com.luke.engine.document.DocumentDtos.ResolveResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.web.server.ResponseStatusException;

/**
 * DOC-3 verification (core side): authorize → finalize → resolve → list → delete, plus the capability
 * gate. Uses the real repo (@DataJpaTest H2) + a mocked CapabilityAccessService + the allow-all context
 * resolver (DOC-4 swaps the latter).
 */
@DataJpaTest
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Autowired DocumentRepository repo;
    @Mock CapabilityAccessService capabilities;

    private DocumentService svc;

    private static final String T = "t1", U = "u1";

    /** No-op scanner (DOC-6 default) → finalize leaves docs READY. */
    private static final DocumentScanner ALLOW_ALL = doc -> DocumentScanner.ScanVerdict.ok();

    @BeforeEach
    void setUp() {
        svc = serviceWith(ALLOW_ALL);
    }

    private DocumentService serviceWith(DocumentScanner scanner) {
        DocumentAccessGuard guard = new DocumentAccessGuard(capabilities, new AllowAllTaskAccessResolver());
        // default-days 0 (no retention for FORMS), signatures-days 2555.
        return new DocumentService(repo, guard, new DocumentRetentionPolicy(0, 2555), scanner);
    }

    private AuthorizeRequest formW9() {
        return new AuthorizeRequest("proc-A", null, Document.KIND_FORM_ATTACHMENT, "FORMS", "f9", "w9.pdf", "application/pdf");
    }

    @Test
    void attachmentAuditSnapshotsReadyDocsAsDocIdToFilenameShaSize() {
        when(capabilities.isAllowed(T, U, "FORMS", true)).thenReturn(true);
        AuthorizeResponse a = svc.authorize(T, U, "User One", formW9());      // ownerEntityId "f9"
        // PENDING is excluded from the audit until finalized READY.
        assertThat(svc.attachmentAudit(T, "FORMS", "f9")).isEmpty();

        svc.finalizeUpload(T, a.docId(), new FinalizeRequest(1234L, "deadbeef"));
        var audit = svc.attachmentAudit(T, "FORMS", "f9");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0))
                .containsEntry("documentId", a.docId())
                .containsEntry("documentName", "w9.pdf")
                .containsEntry("sha256", "sha256:deadbeef")
                .containsEntry("size", "1234");   // size is in BYTES

        // wrong owner / missing owner → empty (no throw)
        assertThat(svc.attachmentAudit(T, "FORMS", "other")).isEmpty();
        assertThat(svc.attachmentAudit(T, "FORMS", null)).isEmpty();
    }

    @Test
    void fullUploadLifecycle() {
        when(capabilities.isAllowed(T, U, "FORMS", true)).thenReturn(true);
        when(capabilities.isAllowed(T, U, "FORMS", false)).thenReturn(true);

        AuthorizeResponse auth = svc.authorize(T, U, "User One", formW9());
        assertThat(auth.docId()).isNotBlank();
        assertThat(auth.storageKey()).startsWith("proc-A/").endsWith("-w9.pdf");

        Document pending = repo.findByIdAndTenantId(auth.docId(), T).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(Document.STATUS_PENDING);
        assertThat(pending.getCreatedBy()).isEqualTo(U);

        svc.finalizeUpload(T, auth.docId(), new FinalizeRequest(1234L, "deadbeef"));
        Document ready = repo.findByIdAndTenantId(auth.docId(), T).orElseThrow();
        assertThat(ready.getStatus()).isEqualTo(Document.STATUS_READY);
        assertThat(ready.getSizeBytes()).isEqualTo(1234L);
        assertThat(ready.getSha256()).isEqualTo("deadbeef");

        ResolveResponse resolved = svc.resolveForDownload(T, U, auth.docId());
        assertThat(resolved.storageKey()).isEqualTo(auth.storageKey());
        assertThat(resolved.filename()).isEqualTo("w9.pdf");

        assertThat(svc.list(T, U, "proc-A", null, null, null)).hasSize(1);

        var drop = svc.delete(T, U, auth.docId());
        assertThat(drop.storageKey()).isEqualTo(auth.storageKey());
        assertThat(drop.hardDelete()).isTrue();   // never under retention → purge every S3 version
        assertThat(repo.findByIdAndTenantId(auth.docId(), T).orElseThrow().getStatus())
                .isEqualTo(Document.STATUS_DELETED);
        // deleted no longer listed
        assertThat(svc.list(T, U, "proc-A", null, null, null)).isEmpty();
    }

    @Test
    void linkProcessInstanceBackfillsExactlyTheRightRowsIdempotently() {
        when(capabilities.isAllowed(T, U, "FORMS", true)).thenReturn(true);
        // two docs under proc-A (processInstanceId null), one under proc-B
        AuthorizeResponse a1 = svc.authorize(T, U, "U", formW9());
        AuthorizeResponse a2 = svc.authorize(T, U, "U", formW9());
        svc.authorize(T, U, "U", new AuthorizeRequest(
                "proc-B", null, Document.KIND_FORM_ATTACHMENT, "FORMS", null, "other.pdf", "application/pdf"));

        int linked = svc.linkProcessInstance(T, "proc-A", "PID-1");
        assertThat(linked).isEqualTo(2);
        assertThat(repo.findByIdAndTenantId(a1.docId(), T).orElseThrow().getProcessInstanceId()).isEqualTo("PID-1");
        assertThat(repo.findByIdAndTenantId(a2.docId(), T).orElseThrow().getProcessInstanceId()).isEqualTo("PID-1");
        // proc-B untouched
        assertThat(repo.findByTenantIdAndProcessRef(T, "proc-B").get(0).getProcessInstanceId()).isNull();
        // idempotent: a second call links nothing new
        assertThat(svc.linkProcessInstance(T, "proc-A", "PID-1")).isZero();
    }

    @Test
    void registerStoredIsIdempotentByStorageKey() {
        // DOC-7: signatures register an already-stored PDF directly (no upload gate), keyed on storageKey.
        var reg = new DocumentRegistration(T, "SR-CODE", null, null,
                Document.KIND_SIGNATURE_ATTACHMENT, "SIGNATURES", "sigReq1",
                "signatures/sigReq1/signed.pdf", "signed.pdf", "application/pdf",
                4096L, "cafef00d", null, U, null);

        Document first = svc.registerStored(reg);
        assertThat(first.getStatus()).isEqualTo(Document.STATUS_READY);
        assertThat(first.getCapability()).isEqualTo("SIGNATURES");

        // a second register on the same (tenant, storageKey) updates the SAME row, not a duplicate
        Document second = svc.registerStored(reg);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(repo.findByTenantIdAndProcessRefOrderByCreatedAtDesc(T, "SR-CODE")).hasSize(1);
    }

    @Test
    void uploadDeniedWithoutCapability() {
        when(capabilities.isAllowed(T, U, "FORMS", true)).thenReturn(false);
        assertThatThrownBy(() -> svc.authorize(T, U, "User One", formW9()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void signaturesGetComplianceRetentionAndCannotBeDeletedEarly() {
        when(capabilities.isAllowed(T, U, "SIGNATURES", true)).thenReturn(true);
        AuthorizeRequest sig = new AuthorizeRequest(
                "proc-S", null, Document.KIND_SIGNATURE_ATTACHMENT, "SIGNATURES", "sr1", "contract.pdf", "application/pdf");

        AuthorizeResponse auth = svc.authorize(T, U, "User One", sig);
        // retention horizon returned to the proxy under COMPLIANCE lock
        assertThat(auth.retainUntilMs()).isNotNull();
        assertThat(auth.objectLockMode()).isEqualTo(DocumentRetentionPolicy.MODE_COMPLIANCE);
        // and persisted ~7y out
        Document d = repo.findByIdAndTenantId(auth.docId(), T).orElseThrow();
        assertThat(d.getRetainUntil()).isAfter(java.time.LocalDateTime.now().plusYears(6));

        // delete refused while under retention → 423 Locked
        assertThatThrownBy(() -> svc.delete(T, U, auth.docId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("423");
    }

    @Test
    void resolveBeforeFinalizeIsConflict() {
        when(capabilities.isAllowed(T, U, "FORMS", true)).thenReturn(true);
        when(capabilities.isAllowed(T, U, "FORMS", false)).thenReturn(true);
        AuthorizeResponse auth = svc.authorize(T, U, "User One", formW9());
        assertThatThrownBy(() -> svc.resolveForDownload(T, U, auth.docId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void infectedScanQuarantinesAndContentIsLocked() {
        when(capabilities.isAllowed(T, U, "FORMS", true)).thenReturn(true);
        when(capabilities.isAllowed(T, U, "FORMS", false)).thenReturn(true);
        DocumentService infectedSvc = serviceWith(doc -> DocumentScanner.ScanVerdict.infected("eicar"));

        AuthorizeResponse auth = infectedSvc.authorize(T, U, "User One", formW9());
        infectedSvc.finalizeUpload(T, auth.docId(), new FinalizeRequest(10L, "abc"));

        assertThat(repo.findByIdAndTenantId(auth.docId(), T).orElseThrow().getStatus())
                .isEqualTo(Document.STATUS_QUARANTINED);
        // quarantined content refuses download → 423 Locked
        assertThatThrownBy(() -> infectedSvc.resolveForDownload(T, U, auth.docId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("423");
    }
}
