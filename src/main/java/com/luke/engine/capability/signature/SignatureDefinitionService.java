package com.luke.engine.capability.signature;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Design-time lifecycle for {@link SignatureDefinition} (mirrors the forms
 * {@code FormDefinitionService}): create → checkout (advisory lock) → edit draft → check-in
 * (immutable {@link SignatureVersion} snapshot) → legal sign-off → publish (gated on sign-off).
 * Plus discard/retire/delete and source-document storage via the {@link DocumentStore}. All
 * operations are tenant-scoped; every mutation writes a {@link SignatureDefinitionAuditEvent}.
 */
@Service
public class SignatureDefinitionService {

    /** Definition statuses (stored on {@code SignatureDefinition.status}). */
    static final String DRAFT = "DRAFT";
    static final String PUBLISHED = "PUBLISHED";
    static final String RETIRED = "RETIRED";

    /** A minimal valid SignatureSchema JSON for a brand-new definition (the builder repairs it). */
    private static final String EMPTY_SCHEMA =
            "{\"document\":{\"name\":\"\"},\"signers\":[{\"id\":\"signer-1\",\"label\":\"Signer 1\",\"order\":1,\"verify\":\"NONE\"}],\"fields\":[],\"routing\":\"sequential\"}";

    private final SignatureDefinitionRepository defs;
    private final SignatureVersionRepository versions;
    private final SignatureDefinitionAuditRepository audit;
    private final DocumentStore documentStore;

    public SignatureDefinitionService(SignatureDefinitionRepository defs,
                                      SignatureVersionRepository versions,
                                      SignatureDefinitionAuditRepository audit,
                                      DocumentStore documentStore) {
        this.defs = defs;
        this.versions = versions;
        this.audit = audit;
        this.documentStore = documentStore;
    }

    // ── reads ──────────────────────────────────────────────────────────────────────────
    public List<SignatureDefinition> list(String tenantId, boolean includeDeleted) {
        return includeDeleted
                ? defs.findByTenantIdOrderByUpdatedAtDescCreatedAtDesc(tenantId)
                : defs.findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDescCreatedAtDesc(tenantId);
    }

    public SignatureDefinition get(String tenantId, String id) {
        return defs.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown signature definition: " + id));
    }

    public Optional<SignatureVersion> latestVersion(String definitionId) {
        return versions.findTopByDefinitionIdOrderByVersionDesc(definitionId);
    }

    public List<SignatureVersion> versions(String tenantId, String id) {
        get(tenantId, id); // tenant guard
        return versions.findByDefinitionIdOrderByVersionAsc(id);
    }

    public SignatureVersion version(String tenantId, String id, int v) {
        get(tenantId, id);
        return versions.findByDefinitionIdAndVersion(id, v)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown version v" + v));
    }

    public List<SignatureDefinitionAuditEvent> auditTrail(String tenantId, String id) {
        get(tenantId, id);
        return audit.findByDefinitionIdOrderByAtAsc(id);
    }

    // ── create + metadata ───────────────────────────────────────────────────────────────
    @Transactional
    public SignatureDefinition create(String tenantId, String userId, String name, String description) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        SignatureDefinition def = new SignatureDefinition();
        def.setTenantId(tenantId);
        def.setCode(uniqueCode(tenantId));
        def.setName(name.trim());
        def.setDescription(description == null ? null : description.trim());
        def.setStatus(DRAFT);
        def.setDraftSchema(EMPTY_SCHEMA);
        def.setCreatedBy(userId);
        def.setUpdatedBy(userId);
        SignatureDefinition saved = defs.save(def);
        record(saved, userId, "created", null);
        return saved;
    }

    @Transactional
    public SignatureDefinition patchMeta(String tenantId, String id, String userId, String name, String description) {
        SignatureDefinition def = get(tenantId, id);
        if (name != null && !name.isBlank()) def.setName(name.trim());
        if (description != null) def.setDescription(description.trim());
        def.setUpdatedBy(userId);
        return defs.save(def);
    }

    // ── draft + versions ────────────────────────────────────────────────────────────────
    @Transactional
    public void saveDraft(String tenantId, String id, String userId, String schema) {
        SignatureDefinition def = get(tenantId, id);
        requireCheckedOutBy(def, userId);
        def.setDraftSchema(schema);
        def.setUpdatedBy(userId);
        defs.save(def);
    }

    @Transactional
    public SignatureVersion checkIn(String tenantId, String id, String userId, String schema) {
        SignatureDefinition def = get(tenantId, id);
        requireCheckedOutBy(def, userId);
        int next = versions.findTopByDefinitionIdOrderByVersionDesc(id).map(v -> v.getVersion() + 1).orElse(1);
        SignatureVersion ver = new SignatureVersion(id, next, schema, userId);
        SignatureVersion saved = versions.save(ver);
        // Mirror the snapshot into the draft, then release the edit lock (back to view-only).
        def.setDraftSchema(schema);
        def.setLockedBy(null);
        def.setLockedAt(null);
        def.setUpdatedBy(userId);
        defs.save(def);
        record(def, userId, "checked_in", "v" + next);
        return saved;
    }

    @Transactional
    public void restoreVersion(String tenantId, String id, int v, String userId) {
        SignatureDefinition def = get(tenantId, id);
        requireCheckedOutBy(def, userId);
        SignatureVersion ver = versions.findByDefinitionIdAndVersion(id, v)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown version v" + v));
        def.setDraftSchema(ver.getSchema());
        def.setUpdatedBy(userId);
        defs.save(def);
        record(def, userId, "version_restored", "v" + v);
    }

    // ── legal review + publish ──────────────────────────────────────────────────────────
    @Transactional
    public SignatureDefinition signOff(String tenantId, String id, String userId) {
        SignatureDefinition def = get(tenantId, id);
        SignatureVersion latest = versions.findTopByDefinitionIdOrderByVersionDesc(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Check in a version before signing off"));
        LocalDateTime now = LocalDateTime.now();
        latest.setSignedOffAt(now);
        latest.setSignedOffBy(userId);
        versions.save(latest);
        def.setLastReviewedAt(now);
        def.setLastReviewedBy(userId);
        def.setUpdatedBy(userId);
        SignatureDefinition saved = defs.save(def);
        record(saved, userId, "signed_off", "v" + latest.getVersion());
        return saved;
    }

    @Transactional
    public void publish(String tenantId, String id, int v, String userId) {
        SignatureDefinition def = get(tenantId, id);
        SignatureVersion target = versions.findByDefinitionIdAndVersion(id, v)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown version v" + v));
        if (target.getSignedOffAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "v" + v + " hasn't been signed off — get legal sign-off before publishing");
        }
        def.setPublishedVersion(v);
        if (!RETIRED.equals(def.getStatus())) def.setStatus(PUBLISHED);
        def.setUpdatedBy(userId);
        defs.save(def);
        record(def, userId, "published", "v" + v);
    }

    // ── advisory edit lock ──────────────────────────────────────────────────────────────
    @Transactional
    public SignatureDefinition checkout(String tenantId, String id, String userId, boolean force) {
        SignatureDefinition def = get(tenantId, id);
        if (RETIRED.equals(def.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Restore the definition before editing it");
        }
        String holder = def.getLockedBy();
        if (holder != null && !holder.equals(userId) && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Locked by " + holder);
        }
        def.setLockedBy(userId);
        def.setLockedAt(LocalDateTime.now());
        def.setUpdatedBy(userId);
        SignatureDefinition saved = defs.save(def);
        record(saved, userId, "checked_out", force && holder != null && !holder.equals(userId) ? "forced from " + holder : null);
        return saved;
    }

    @Transactional
    public void release(String tenantId, String id, String userId, boolean force) {
        SignatureDefinition def = get(tenantId, id);
        String holder = def.getLockedBy();
        if (holder != null && !holder.equals(userId) && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Locked by " + holder);
        }
        def.setLockedBy(null);
        def.setLockedAt(null);
        def.setUpdatedBy(userId);
        defs.save(def);
        record(def, userId, "released", null);
    }

    @Transactional
    public void discard(String tenantId, String id, String userId) {
        SignatureDefinition def = get(tenantId, id);
        // Revert the draft to the published version, else the latest checked-in version, else empty.
        String revertTo = EMPTY_SCHEMA;
        Integer pub = def.getPublishedVersion();
        Optional<SignatureVersion> source = pub != null
                ? versions.findByDefinitionIdAndVersion(id, pub)
                : versions.findTopByDefinitionIdOrderByVersionDesc(id);
        if (source.isPresent()) revertTo = source.get().getSchema();
        def.setDraftSchema(revertTo);
        def.setLockedBy(null);
        def.setLockedAt(null);
        def.setUpdatedBy(userId);
        defs.save(def);
        record(def, userId, "discarded", null);
    }

    // ── archive + delete ────────────────────────────────────────────────────────────────
    @Transactional
    public void retire(String tenantId, String id, String userId) {
        SignatureDefinition def = get(tenantId, id);
        def.setStatus(RETIRED);
        def.setUpdatedBy(userId);
        defs.save(def);
        record(def, userId, "retired", null);
    }

    @Transactional
    public void unretire(String tenantId, String id, String userId) {
        SignatureDefinition def = get(tenantId, id);
        def.setStatus(def.getPublishedVersion() != null ? PUBLISHED : DRAFT);
        def.setUpdatedBy(userId);
        defs.save(def);
        record(def, userId, "unretired", null);
    }

    @Transactional
    public void softDelete(String tenantId, String id, String userId) {
        SignatureDefinition def = get(tenantId, id);
        def.setDeletedAt(LocalDateTime.now());
        def.setLockedBy(null);
        def.setLockedAt(null);
        def.setUpdatedBy(userId);
        defs.save(def);
        record(def, userId, "deleted", null);
    }

    @Transactional
    public void restore(String tenantId, String id, String userId) {
        SignatureDefinition def = get(tenantId, id);
        def.setDeletedAt(null);
        def.setUpdatedBy(userId);
        defs.save(def);
        record(def, userId, "restored", null);
    }

    @Transactional
    public void purge(String tenantId, String id) {
        SignatureDefinition def = get(tenantId, id);
        if (def.getDocumentKey() != null) {
            try {
                documentStore.delete(tenantId, def.getDocumentKey());
            } catch (RuntimeException ignored) {
                // best-effort; the row goes regardless
            }
        }
        versions.deleteByDefinitionId(id);
        audit.deleteByDefinitionId(id);
        defs.delete(def);
    }

    // ── source document (DocumentStore) ──────────────────────────────────────────────────
    public record UploadedDocument(String key, String name, int pageCount) {}

    @Transactional
    public UploadedDocument uploadDocument(String tenantId, String id, String userId, byte[] pdf, String filename) {
        SignatureDefinition def = get(tenantId, id);
        requireCheckedOutBy(def, userId);
        if (!isPdf(pdf)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "file must be a PDF");
        }
        int pages = pageCount(pdf);
        String key = UUID.randomUUID().toString().replace("-", "") + ".pdf";
        documentStore.put(tenantId, key, pdf, "application/pdf");
        def.setDocumentKey(key);
        def.setUpdatedBy(userId);
        defs.save(def);
        String name = (filename == null || filename.isBlank()) ? "document.pdf" : filename;
        record(def, userId, "document_uploaded", name + " (" + pages + " pages)");
        return new UploadedDocument(key, name, pages);
    }

    public byte[] fetchDocument(String tenantId, String id, String key) {
        SignatureDefinition def = get(tenantId, id);
        if (def.getDocumentKey() == null || !def.getDocumentKey().equals(key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown document");
        }
        return documentStore.get(tenantId, key);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────
    private void requireCheckedOutBy(SignatureDefinition def, String userId) {
        String holder = def.getLockedBy();
        if (holder == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Check out the definition to edit it");
        }
        if (userId != null && !holder.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Locked by " + holder);
        }
    }

    private String uniqueCode(String tenantId) {
        for (int i = 0; i < 8; i++) {
            String code = SignatureSupport.generateDefinitionCode();
            if (!defs.existsByTenantIdAndCode(tenantId, code)) return code;
        }
        throw new IllegalStateException("Could not generate a unique signature definition code");
    }

    private SignatureDefinitionAuditEvent record(SignatureDefinition def, String userId, String action, String detail) {
        return audit.save(new SignatureDefinitionAuditEvent(def.getId(), def.getTenantId(), action, userId, detail));
    }

    private static boolean isPdf(byte[] b) {
        return b != null && b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-';
    }

    private static int pageCount(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Could not read the PDF");
        }
    }
}
