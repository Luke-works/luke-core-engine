package com.luke.engine.document;

import com.luke.engine.document.DocumentDtos.AuthorizeRequest;
import com.luke.engine.document.DocumentDtos.AuthorizeResponse;
import com.luke.engine.document.DocumentDtos.DocumentDto;
import com.luke.engine.document.DocumentDtos.FinalizeRequest;
import com.luke.engine.document.DocumentDtos.ResolveResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * The DOCUMENTS state + authZ brain (DOC-3, core side). luke-file-proxy calls this over the internal
 * hop to authorize an upload (→ docId + storage key), finalize it, resolve it for download, or delete
 * it. Bytes NEVER reach core — only this small structured state does.
 */
@Service
public class DocumentService {

    private final DocumentRepository repo;
    private final DocumentAccessGuard guard;
    private final DocumentRetentionPolicy retention;
    private final DocumentScanner scanner;

    public DocumentService(DocumentRepository repo, DocumentAccessGuard guard,
                           DocumentRetentionPolicy retention, DocumentScanner scanner) {
        this.repo = repo;
        this.guard = guard;
        this.retention = retention;
        this.scanner = scanner;
    }

    /** Authorize an upload: gate it, then create a PENDING row with a fresh docId + storage key. */
    @Transactional
    public AuthorizeResponse authorize(String tenantId, String userId, String userName, AuthorizeRequest req) {
        require("processRef", req.processRef());
        require("kind", req.kind());
        require("capability", req.capability());
        require("filename", req.filename());
        require("contentType", req.contentType());
        validateSegment(req.processRef());

        guard.requireUpload(tenantId, userId, req.capability(), req.processRef(), null, req.taskId());

        Document d = new Document();
        d.setId(UUID.randomUUID().toString());
        d.setTenantId(tenantId);
        d.setProcessRef(req.processRef());
        d.setTaskId(blankToNull(req.taskId()));
        d.setKind(req.kind());
        d.setCapability(req.capability());
        d.setOwnerEntityId(blankToNull(req.ownerEntityId()));
        d.setFilename(req.filename());
        d.setContentType(req.contentType());
        d.setStatus(Document.STATUS_PENDING);
        d.setCreatedBy(blankToNull(userId));
        d.setCreatedByName(blankToNull(userName));
        // Retention horizon (DOC-5): per-capability default, or a caller-supplied override.
        LocalDateTime retainUntil = retention.resolveRetainUntil(req.capability(), req.retainUntilMs());
        d.setRetainUntil(retainUntil);
        // TENANT-RELATIVE key; BlobStore prepends {tenantId}/. Physical S3 key = {tenant}/{processRef}/{docId}-{file}
        d.setStorageKey(req.processRef() + "/" + d.getId() + "-" + slug(req.filename()));
        repo.save(d);

        // Hand the proxy the retention to stamp as S3 Object Lock on PUT (it holds the only S3 creds).
        Long retainMs = retainUntil == null ? null : retainUntil.toInstant(ZoneOffset.UTC).toEpochMilli();
        return new AuthorizeResponse(d.getId(), d.getStorageKey(), retainMs,
                retention.lockMode(req.capability(), retainUntil));
    }

    /**
     * Register an already-stored blob in the shared index (DOC-7) — no upload gate (the owning
     * capability already authorized the write). Idempotent on {@code (tenantId, storageKey)}: a repeat
     * (e.g. re-seal) updates the existing row. Runs in its OWN transaction so a registry hiccup can
     * never roll back the caller's capability flow (callers invoke it best-effort).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Document registerStored(DocumentRegistration r) {
        require("processRef", r.processRef());
        require("storageKey", r.storageKey());
        require("capability", r.capability());
        Document d = repo.findByTenantIdAndStorageKey(r.tenantId(), r.storageKey()).orElseGet(Document::new);
        if (d.getId() == null) {
            d.setId(UUID.randomUUID().toString());
        }
        d.setTenantId(r.tenantId());
        d.setProcessRef(r.processRef());
        d.setProcessInstanceId(blankToNull(r.processInstanceId()));
        d.setTaskId(blankToNull(r.taskId()));
        d.setKind(r.kind() != null ? r.kind() : Document.KIND_GENERIC);
        d.setCapability(r.capability());
        d.setOwnerEntityId(blankToNull(r.ownerEntityId()));
        d.setStorageKey(r.storageKey());
        d.setFilename(r.filename() != null ? r.filename() : "file");
        d.setContentType(r.contentType() != null ? r.contentType() : "application/octet-stream");
        d.setSizeBytes(r.sizeBytes());
        d.setSha256(r.sha256());
        d.setStatus(Document.STATUS_READY);
        d.setRetainUntil(r.retainUntil());
        d.setCreatedBy(blankToNull(r.createdBy()));
        d.setCreatedByName(blankToNull(r.createdByName()));
        return repo.save(d);
    }

    /** Finalize: the proxy reports the streamed size + checksum; flip PENDING → READY. */
    @Transactional
    public DocumentDto finalizeUpload(String tenantId, String docId, FinalizeRequest req) {
        Document d = repo.findByIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> notFound());
        if (!Document.STATUS_PENDING.equals(d.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "document already finalized");
        }
        d.setSizeBytes(req.sizeBytes());
        d.setSha256(req.sha256());
        d.setStatus(Document.STATUS_READY);
        // DOC-6 integrity/AV seam: a verdict of infected quarantines (→ 423 on /content). No-op in V1.
        DocumentScanner.ScanVerdict verdict = scanner.scan(d);
        if (verdict != null && !verdict.clean()) {
            d.setStatus(Document.STATUS_QUARANTINED);
        }
        repo.save(d);
        return DocumentDto.of(d);
    }

    /**
     * Flow-A backfill (DOC-9): once a process starts with {@code businessKey=processRef}, stamp its
     * Camunda {@code processInstanceId} onto every document uploaded under that processRef before the
     * instance existed. Idempotent (only changed rows are written); no S3 rename — the key is immutable.
     * Returns the number of rows newly linked.
     */
    @Transactional
    public int linkProcessInstance(String tenantId, String processRef, String processInstanceId) {
        require("processRef", processRef);
        require("processInstanceId", processInstanceId);
        List<Document> rows = repo.findByTenantIdAndProcessRef(tenantId, processRef);
        int linked = 0;
        for (Document d : rows) {
            if (!processInstanceId.equals(d.getProcessInstanceId())) {
                d.setProcessInstanceId(processInstanceId);
                linked++;
            }
        }
        if (linked > 0) {
            repo.saveAll(rows);
        }
        return linked;
    }

    /** Resolve for download: gate it, then hand the proxy the storage key to stream from. */
    @Transactional(readOnly = true)
    public ResolveResponse resolveForDownload(String tenantId, String userId, String docId) {
        Document d = repo.findByIdAndTenantId(docId, tenantId).orElseThrow(() -> notFound());
        guard.requireRead(tenantId, userId, d);
        if (Document.STATUS_QUARANTINED.equals(d.getStatus())) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "document quarantined");
        }
        if (!Document.STATUS_READY.equals(d.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "document not ready");
        }
        return new ResolveResponse(d.getId(), d.getStorageKey(), d.getContentType(), d.getFilename(), d.getStatus());
    }

    /** Metadata for one doc (gated). */
    @Transactional(readOnly = true)
    public DocumentDto metadata(String tenantId, String userId, String docId) {
        Document d = repo.findByIdAndTenantId(docId, tenantId).orElseThrow(() -> notFound());
        guard.requireRead(tenantId, userId, d);
        return DocumentDto.of(d);
    }

    /** List a case file / task / capability-owner, FILTERED to what the caller may see. */
    @Transactional(readOnly = true)
    public List<DocumentDto> list(String tenantId, String userId, String processRef, String taskId,
                                  String capability, String ownerEntityId) {
        List<Document> rows;
        if (StringUtils.hasText(taskId)) {
            rows = repo.findByTenantIdAndTaskIdOrderByCreatedAtDesc(tenantId, taskId);
        } else if (StringUtils.hasText(processRef)) {
            rows = repo.findByTenantIdAndProcessRefOrderByCreatedAtDesc(tenantId, processRef);
        } else if (StringUtils.hasText(capability) && StringUtils.hasText(ownerEntityId)) {
            rows = repo.findByTenantIdAndCapabilityAndOwnerEntityIdOrderByCreatedAtDesc(tenantId, capability, ownerEntityId);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provide processRef, taskId, or capability+ownerEntityId");
        }
        return rows.stream()
                .filter(d -> !Document.STATUS_DELETED.equals(d.getStatus()))
                .filter(d -> guard.mayRead(tenantId, userId, d))
                .map(DocumentDto::of)
                .toList();
    }

    /** Soft-delete (gated + retention-checked); returns the storage key so the proxy can drop the bytes. */
    @Transactional
    public String delete(String tenantId, String userId, String docId) {
        Document d = repo.findByIdAndTenantId(docId, tenantId).orElseThrow(() -> notFound());
        guard.requireWrite(tenantId, userId, d);
        if (d.getRetainUntil() != null && d.getRetainUntil().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "document under retention until " + d.getRetainUntil());
        }
        d.setStatus(Document.STATUS_DELETED);
        d.setDeletedAt(LocalDateTime.now());
        repo.save(d);
        return d.getStorageKey();
    }

    // ── anonymous (embed-token-scoped) flow ──────────────────────────────────────
    // No session user / capability / candidate-group gate: the caller proved (upstream) a valid embed
    // token for a published form in this tenant, so the tenant + processRef ARE the scope. Used only by
    // EmbedDocumentService behind the public document broker.

    /** Authorize an anonymous upload → PENDING row + storage key (tenant comes from the verified token). */
    @Transactional
    public AuthorizeResponse authorizeAnonymous(String tenantId, String capability, String kind,
                                                String processRef, String filename, String contentType) {
        require("processRef", processRef);
        require("filename", filename);
        require("contentType", contentType);
        validateSegment(processRef);

        Document d = new Document();
        d.setId(UUID.randomUUID().toString());
        d.setTenantId(tenantId);
        d.setProcessRef(processRef);
        d.setKind(kind != null ? kind : Document.KIND_FORM_ATTACHMENT);
        d.setCapability(capability);
        d.setFilename(filename);
        d.setContentType(contentType);
        d.setStatus(Document.STATUS_PENDING);
        d.setCreatedBy(null);                  // anonymous embed respondent
        d.setCreatedByName(null);
        LocalDateTime retainUntil = retention.resolveRetainUntil(capability, null);
        d.setRetainUntil(retainUntil);
        d.setStorageKey(processRef + "/" + d.getId() + "-" + slug(filename));
        repo.save(d);

        Long retainMs = retainUntil == null ? null : retainUntil.toInstant(ZoneOffset.UTC).toEpochMilli();
        return new AuthorizeResponse(d.getId(), d.getStorageKey(), retainMs,
                retention.lockMode(capability, retainUntil));
    }

    /** Active (non-deleted) attachment count for a (tenant, processRef) — the per-session abuse cap. */
    @Transactional(readOnly = true)
    public long countActiveAnonymous(String tenantId, String processRef) {
        return repo.findByTenantIdAndProcessRef(tenantId, processRef).stream()
                .filter(d -> !Document.STATUS_DELETED.equals(d.getStatus()))
                .count();
    }

    /** List a (tenant, processRef) case file — no per-user filtering (token+processRef is the scope). */
    @Transactional(readOnly = true)
    public List<DocumentDto> listAnonymous(String tenantId, String processRef) {
        return repo.findByTenantIdAndProcessRefOrderByCreatedAtDesc(tenantId, processRef).stream()
                .filter(d -> !Document.STATUS_DELETED.equals(d.getStatus()))
                .map(DocumentDto::of)
                .toList();
    }

    /** Soft-delete an anonymous upload, scoped to its (tenant, processRef); returns the storage key. */
    @Transactional
    public String deleteAnonymous(String tenantId, String processRef, String docId) {
        Document d = repo.findByIdAndTenantId(docId, tenantId).orElseThrow(DocumentService::notFound);
        if (!processRef.equals(d.getProcessRef())) {
            throw notFound();                   // doc isn't in this session's case file
        }
        if (d.getRetainUntil() != null && d.getRetainUntil().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "document under retention");
        }
        d.setStatus(Document.STATUS_DELETED);
        d.setDeletedAt(LocalDateTime.now());
        repo.save(d);
        return d.getStorageKey();
    }

    /** On embed submit: bind a session's uploads to the created form instance (ownerEntityId + processInstanceId). */
    @Transactional
    public int linkToInstance(String tenantId, String processRef, String instanceId) {
        List<Document> rows = repo.findByTenantIdAndProcessRef(tenantId, processRef);
        int linked = 0;
        for (Document d : rows) {
            if (!instanceId.equals(d.getOwnerEntityId())) {
                d.setOwnerEntityId(instanceId);
                d.setProcessInstanceId(instanceId);   // the form instance is the process anchor for an embed submission
                linked++;
            }
        }
        if (linked > 0) {
            repo.saveAll(rows);
        }
        return linked;
    }

    // ── helpers ────────────────────────────────────────────────────────────────
    private static void require(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
    }

    private static void validateSegment(String value) {
        if (value.contains("/") || value.contains("..") || value.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "processRef must be a single safe path segment");
        }
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    /** Filesystem/S3-safe filename for the key suffix (keeps a readable name, strips the rest). */
    private static String slug(String filename) {
        String base = filename == null ? "file" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank()) base = "file";
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found");
    }
}
