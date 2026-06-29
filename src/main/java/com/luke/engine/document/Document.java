package com.luke.engine.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * System-of-record row for one stored document (DOC-2). The BYTES live in object storage
 * (S3, behind luke-file-proxy); this row is the durable, queryable, tenant-isolated index +
 * authZ source + status machine that ties a stable {@code docId} to its storage key.
 *
 * <p>Tenancy mirrors {@code SignatureRequest}: an explicit {@code tenantId} column + tenant-scoped
 * repository finders (NOT the {@code TenantAwareEntity} @Filter — no real entity uses it). Under the
 * postgres profile Hibernate ddl-auto is none, so {@code V7__document_table.sql} is the schema source
 * of truth and must stay faithful to this entity.
 *
 * <p>Process-centric "case file": a document belongs to a process ({@code processRef}, the stable
 * business key) and optionally a task ({@code taskId}). {@code taskId} null = process/case-level
 * attachment; set = task attachment (always also process-scoped) — mirrors Camunda
 * {@code ACT_HI_ATTACHMENT} (TASK_ID_ + PROC_INST_ID_), which is what makes per-group/per-task
 * access restriction possible (DOC-4 resolves candidate groups from {@code processInstanceId}/{@code taskId}).
 */
@Entity
@Table(
    name = "luke_document",
    indexes = {
        @Index(name = "idx_document_tenant_process", columnList = "tenantId,processRef"),
        @Index(name = "idx_document_tenant_task", columnList = "tenantId,taskId"),
        @Index(name = "idx_document_tenant_cap_owner", columnList = "tenantId,capability,ownerEntityId"),
        @Index(name = "idx_document_proc_instance", columnList = "processInstanceId"),
        @Index(name = "idx_document_retain", columnList = "retainUntil")
    }
)
public class Document {

    /** Allowed {@link #kind} values (plain varchar, no DB CHECK — mirrors the codebase convention). */
    public static final String KIND_FORM_ATTACHMENT = "FORM_ATTACHMENT";
    public static final String KIND_SIGNATURE_ATTACHMENT = "SIGNATURE_ATTACHMENT";
    public static final String KIND_GENERIC = "GENERIC";

    /** Allowed {@link #status} values. */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_QUARANTINED = "QUARANTINED";
    public static final String STATUS_DELETED = "DELETED";

    /** The stable, app-owned docId handed to clients. Assigned by core at authorize-time (so the
     *  storage key can be built from it in one save); @PrePersist assigns one if left null. */
    @Id
    private String id;

    /** Optimistic lock: concurrent authorize/finalize/delete on the same row → 409, not a clobber. */
    @Version
    private Long version;

    @Column(nullable = false)
    private String tenantId;

    // ── process / task linkage ────────────────────────────────────────────────
    /** The process business key = the case-file folder + S3 key prefix. Always set. */
    @Column(nullable = false)
    private String processRef;

    /** Camunda system instance id. Null until the process starts (start-with-attachments); backfilled (DOC-9). */
    @Column
    private String processInstanceId;

    /** Camunda task id. Null = process/case-level attachment; set = task attachment (also process-scoped). */
    @Column
    private String taskId;

    // ── classification ────────────────────────────────────────────────────────
    /** FORM_ATTACHMENT | SIGNATURE_ATTACHMENT | GENERIC. */
    @Column(nullable = false)
    private String kind;

    /** Owning capability (FORMS | SIGNATURES | EMAIL) — drives the capability authZ layer. */
    @Column(nullable = false)
    private String capability;

    /** Owning entity within the capability (formInstanceId / signatureRequestId), optional. */
    @Column
    private String ownerEntityId;

    // ── storage + integrity ───────────────────────────────────────────────────
    /** The S3 object key. SERVER-ONLY — never serialized to a client. Built at authorize-time. */
    @Column(nullable = false)
    private String storageKey;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String contentType;

    /** Bytes; null until finalize confirms it (the proxy streams, then reports the real size). */
    @Column
    private Long sizeBytes;

    /** SHA-256 hex; null until finalize (computed server-side as bytes stream through the proxy). */
    @Column(length = 64)
    private String sha256;

    // ── lifecycle ─────────────────────────────────────────────────────────────
    /** PENDING (authorized, upload in flight) → READY (finalized) | QUARANTINED | DELETED. */
    @Column(nullable = false)
    private String status = STATUS_PENDING;

    /** Object-Lock retention horizon (DOC-5). Null = no retention. */
    @Column
    private LocalDateTime retainUntil;

    // ── audit ─────────────────────────────────────────────────────────────────
    @Column
    private String createdBy;

    @Column
    private String createdByName;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime deletedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = STATUS_PENDING;
    }

    // ── accessors (explicit, mirroring SignatureRequest) ───────────────────────
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getProcessRef() { return processRef; }
    public void setProcessRef(String processRef) { this.processRef = processRef; }

    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getCapability() { return capability; }
    public void setCapability(String capability) { this.capability = capability; }

    public String getOwnerEntityId() { return ownerEntityId; }
    public void setOwnerEntityId(String ownerEntityId) { this.ownerEntityId = ownerEntityId; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getRetainUntil() { return retainUntil; }
    public void setRetainUntil(LocalDateTime retainUntil) { this.retainUntil = retainUntil; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
