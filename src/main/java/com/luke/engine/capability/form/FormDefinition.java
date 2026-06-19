package com.luke.engine.capability.form;

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
 * A form template (the "definition"), versioned and tenant-scoped. The editable
 * working draft lives in {@code draftSchema}; immutable checked-in versions are
 * {@link FormVersion} rows. {@code publishedVersion} is the one consumers (the
 * renderer, a process via formKey) resolve to.
 *
 * <p>Addressed externally by {@code code} (FM-XXXX-DDMMMYY), stable across
 * environments; {@code id} is internal. Lives in the capability engine's own
 * schema alongside {@link com.luke.engine.capability.capability.Capability}.
 */
@Entity
@Table(
    name = "luke_form_definitions",
    uniqueConstraints = @UniqueConstraint(name = "uq_form_tenant_code", columnNames = {"tenantId", "code"}),
    indexes = @Index(name = "idx_form_tenant", columnList = "tenantId")
)
public class FormDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Optimistic-locking token (#57): concurrent draft saves / lock checkouts that
     *  read-then-write the same row collide → OptimisticLockException → 409, instead of
     *  a silent last-write-wins clobber. JPA manages it. */
    @Version
    private Long version;

    @Column(nullable = false)
    private String tenantId;

    /** Stable human id, e.g. "FM-XKQW-05JUN26". Unique per tenant. */
    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    /** Lifecycle: DRAFT, PUBLISHED, RETIRED. */
    @Column(nullable = false)
    private String status = "DRAFT";

    /** The version consumers resolve to via {@code @published}; null until first publish. */
    private Integer publishedVersion;

    /** Editable working draft (coltorapps schema JSON). Not what processes consume. */
    @Column(columnDefinition = "text")
    private String draftSchema;

    /** Soft-delete marker (trash); null = live. */
    private LocalDateTime deletedAt;

    /** Advisory edit-lock holder (userId), or null when unlocked. Set on checkout,
     *  cleared on release / check-in / discard. Stale locks can be taken over. */
    private String lockedBy;
    private LocalDateTime lockedAt;

    private String createdBy;
    private String updatedBy;

    /** Resolved display names for created_by / updated_by — filled at read time, NOT persisted. */
    @Transient
    private String createdByName;
    @Transient
    private String updatedByName;

    /** When the form last passed its self-test ("Test the form"), and by whom. */
    private LocalDateTime lastTestedAt;
    private String lastTestedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public FormDefinition() {}

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

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getPublishedVersion() { return publishedVersion; }
    public void setPublishedVersion(Integer publishedVersion) { this.publishedVersion = publishedVersion; }

    public String getDraftSchema() { return draftSchema; }
    public void setDraftSchema(String draftSchema) { this.draftSchema = draftSchema; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }

    public LocalDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(LocalDateTime lockedAt) { this.lockedAt = lockedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }

    public LocalDateTime getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(LocalDateTime lastTestedAt) { this.lastTestedAt = lastTestedAt; }

    public String getLastTestedBy() { return lastTestedBy; }
    public void setLastTestedBy(String lastTestedBy) { this.lastTestedBy = lastTestedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
