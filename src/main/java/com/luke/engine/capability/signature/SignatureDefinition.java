package com.luke.engine.capability.signature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

/**
 * Design-time SIGNATURE DEFINITION — the compile-time half of the capability (mirrors
 * {@code FormDefinition} in luke-core-engine). An editable {@code draftSchema} (a
 * SignatureSchema JSON: document key + signer roles + placed fields + routing) plus a chain of
 * immutable {@link SignatureVersion}s. Legal review = per-version sign-off; {@code publish} is
 * gated on a signed-off version, which runtime instances then pin.
 *
 * <p>Tenant-scoped; addressed externally by {@code code} (SD-XXXX-DDMMMYY). The source PDF lives
 * in the {@link DocumentStore} — only its {@code documentKey} is kept here, never the bytes.
 * DB-PORTABLE (string status/enums, {@code text} JSON columns) for a clean H2&rarr;Postgres swap.
 */
@Entity
@Table(
    name = "luke_signature_definitions",
    uniqueConstraints = @UniqueConstraint(name = "uq_sigdef_tenant_code", columnNames = {"tenantId", "code"}),
    indexes = {
        @Index(name = "idx_sigdef_tenant_status", columnList = "tenantId,status"),
        @Index(name = "idx_sigdef_tenant_updated", columnList = "tenantId,updatedAt")
    }
)
public class SignatureDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Optimistic-locking token: concurrent draft saves / lifecycle writes collide → 409. */
    @Version
    private Long version;

    @Column(nullable = false)
    private String tenantId;

    /** Stable human id, e.g. "SD-XKQW-27JUN26". Unique per tenant. */
    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    /** DRAFT | PUBLISHED | RETIRED (string for DB portability). */
    @Column(nullable = false, length = 32)
    private String status = "DRAFT";

    /** The live published version, or null until first publish. */
    private Integer publishedVersion;

    /** Editable working draft (SignatureSchema JSON). Opaque to the engine. */
    @Column(columnDefinition = "text")
    private String draftSchema;

    /** Most recently uploaded source-document key (DocumentStore). */
    private String documentKey;

    /** Advisory edit lock: holder userId + when acquired. */
    private String lockedBy;
    private LocalDateTime lockedAt;

    /** Last legal review pass (mirrored from the latest version sign-off, for the builder badge). */
    private LocalDateTime lastReviewedAt;
    private String lastReviewedBy;

    private String createdBy;
    private String updatedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    /** Soft-delete tombstone (null = live). */
    private LocalDateTime deletedAt;

    /** Resolved at read time from the user directory; never persisted. */
    @Transient
    private String createdByName;
    @Transient
    private String updatedByName;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── getters / setters ─────────────────────────────────────────────────────────────
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getPublishedVersion() { return publishedVersion; }
    public void setPublishedVersion(Integer publishedVersion) { this.publishedVersion = publishedVersion; }
    public String getDraftSchema() { return draftSchema; }
    public void setDraftSchema(String draftSchema) { this.draftSchema = draftSchema; }
    public String getDocumentKey() { return documentKey; }
    public void setDocumentKey(String documentKey) { this.documentKey = documentKey; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }
    public LocalDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(LocalDateTime lockedAt) { this.lockedAt = lockedAt; }
    public LocalDateTime getLastReviewedAt() { return lastReviewedAt; }
    public void setLastReviewedAt(LocalDateTime lastReviewedAt) { this.lastReviewedAt = lastReviewedAt; }
    public String getLastReviewedBy() { return lastReviewedBy; }
    public void setLastReviewedBy(String lastReviewedBy) { this.lastReviewedBy = lastReviewedBy; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
}
