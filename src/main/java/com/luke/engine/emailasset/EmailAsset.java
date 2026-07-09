package com.luke.engine.emailasset;

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
 * System-of-record row for one PUBLIC email asset (an image uploaded in the email builder). This is a
 * DEDICATED store, separate from {@code luke_document}: email images are inherently public — they load
 * in a recipient's mail client via a bare {@code <img src>} with no auth — so they are served by a
 * public, id-as-bearer route ({@code /api/public/email-assets/{id}}), never behind the tenant/capability
 * gate that private documents require. The bytes live in S3 (behind luke-file-proxy); this row maps the
 * stable {@code assetId} to its storage key and is tenant + template scoped.
 *
 * <p>Tenancy mirrors {@code Document}: an explicit {@code tenantId} column + tenant-scoped finders.
 * Under the postgres profile Hibernate ddl-auto is {@code none}, so {@code V13__email_asset_table.sql}
 * is the schema source of truth and must stay faithful to this entity.
 */
@Entity
@Table(
    name = "luke_email_asset",
    indexes = {
        @Index(name = "idx_email_asset_tenant_template", columnList = "tenantId,templateId"),
        @Index(name = "idx_email_asset_tenant", columnList = "tenantId")
    }
)
public class EmailAsset {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_READY = "READY";

    /** Stable, app-owned assetId handed to clients — also the sole authorization for the public GET,
     *  so it is a high-entropy UUID (unguessable). Assigned at authorize-time; @PrePersist backfills. */
    @Id
    private String id;

    /** Optimistic lock: concurrent authorize/finalize on the same row → 409, not a clobber. */
    @Version
    private Long version;

    @Column(nullable = false)
    private String tenantId;

    /** The email template this asset was uploaded for (organizational scope + cleanup). */
    @Column
    private String templateId;

    /** TENANT-RELATIVE storage key ({@code email-assets/{templateId}/{assetId}-{file}}); BlobStore
     *  prepends {@code {tenantId}/}. Server-only — never returned to clients. */
    @Column(nullable = false)
    private String storageKey;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String contentType;

    @Column
    private Long sizeBytes;

    @Column(length = 64)
    private String sha256;

    /** PENDING → READY. Only READY assets are served publicly. */
    @Column(nullable = false)
    private String status;

    @Column
    private String createdBy;

    @Column
    private String createdByName;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = STATUS_PENDING;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
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
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
