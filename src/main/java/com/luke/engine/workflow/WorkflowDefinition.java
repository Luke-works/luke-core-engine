package com.luke.engine.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

/**
 * A workflow template (the "definition"), versioned and tenant-scoped. The editable
 * working draft lives in {@code draftJson}; immutable checked-in snapshots are
 * {@link WorkflowVersion} rows. {@code publishedVersion} is the one deployed to
 * Camunda and resolved at runtime.
 *
 * <p>Mirrors {@code FormDefinition}/{@code FormVersion} — the design-time lifecycle
 * reference (check-in = snapshot, per-version sign-off, publish gated on a signed-off
 * version).
 */
@Entity
@Table(
    name = "luke_workflow_definitions",
    indexes = @Index(name = "idx_wfdef_tenant", columnList = "tenantId")
)
public class WorkflowDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Optimistic-locking token — concurrent draft saves collide → 409 instead of clobber. */
    @Version
    private Long lockVersion;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    private String description;

    /** The editable working draft (workflow JSON DSL). */
    @Column(columnDefinition = "text")
    private String draftJson;

    /** {@code DRAFT} until a signed-off version is published, then {@code PUBLISHED}. */
    @Column(nullable = false)
    private String status = "DRAFT";

    /** Highest check-in version number so far (0 = never checked in). */
    @Column(nullable = false)
    private int latestVersion = 0;

    /** The currently-deployed version, or null if never published. */
    private Integer publishedVersion;

    private String createdBy;
    private String updatedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getLockVersion() { return lockVersion; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDraftJson() { return draftJson; }
    public void setDraftJson(String draftJson) { this.draftJson = draftJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getLatestVersion() { return latestVersion; }
    public void setLatestVersion(int latestVersion) { this.latestVersion = latestVersion; }

    public Integer getPublishedVersion() { return publishedVersion; }
    public void setPublishedVersion(Integer publishedVersion) { this.publishedVersion = publishedVersion; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
