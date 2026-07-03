package com.luke.engine.capability.emailtemplate;

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
import java.time.LocalDateTime;

/**
 * An email template (the "definition"), versioned and tenant-scoped. The editable
 * working draft lives in {@code draftDoc} (the bounded EmailDoc JSON the AI emits);
 * immutable checked-in versions are {@link EmailTemplateVersion} rows.
 * {@code publishedVersion} is the one referenced when sending a test.
 *
 * <p>Addressed externally by {@code code} (ET-XXXX-DDMMMYY), stable across
 * environments; {@code id} is internal. Mirrors {@link com.luke.engine.capability.form.FormDefinition}.
 *
 * <p>The rendered HTML lives in Postmark (keyed by {@code postmarkAlias}), never
 * here — we only store the lightweight EmailDoc JSON.
 */
@Entity
@Table(
    name = "luke_email_templates",
    uniqueConstraints = @UniqueConstraint(name = "uq_emailtpl_tenant_code", columnNames = {"tenantId", "code"}),
    indexes = @Index(name = "idx_emailtpl_tenant", columnList = "tenantId")
)
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** Stable human id, e.g. "ET-XKQW-05JUN26". Unique per tenant. */
    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    /** Cached subject of the draft/published doc (may contain {{vars}}). */
    private String subject;

    /** Lifecycle: DRAFT, PUBLISHED, RETIRED. */
    @Column(nullable = false)
    private String status = "DRAFT";

    /** The version send-test references; null until first publish. */
    private Integer publishedVersion;

    /** Editable working draft (EmailDoc JSON). Autosave target; not pushed to Postmark until check-in. */
    @Column(columnDefinition = "text")
    private String draftDoc;

    /** Postmark template alias once published; stable across versions so re-publish updates the same template. */
    private String postmarkAlias;

    /** Soft-delete marker (trash); null = live. */
    private LocalDateTime deletedAt;

    private String createdBy;
    private String updatedBy;

    /** Resolved display names for created_by / updated_by — filled at read time, NOT persisted. */
    @Transient
    private String createdByName;
    @Transient
    private String updatedByName;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public EmailTemplate() {}

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getPublishedVersion() { return publishedVersion; }
    public void setPublishedVersion(Integer publishedVersion) { this.publishedVersion = publishedVersion; }

    public String getDraftDoc() { return draftDoc; }
    public void setDraftDoc(String draftDoc) { this.draftDoc = draftDoc; }

    public String getPostmarkAlias() { return postmarkAlias; }
    public void setPostmarkAlias(String postmarkAlias) { this.postmarkAlias = postmarkAlias; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
