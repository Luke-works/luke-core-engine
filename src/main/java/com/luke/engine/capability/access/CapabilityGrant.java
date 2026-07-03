package com.luke.engine.capability.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * A per-user access level on a capability, within a tenant — the second layer on
 * top of the tenant-wide {@link com.luke.engine.capability.capability.CapabilitySubscription}.
 *
 * <p>Access requires BOTH: the tenant is subscribed to the capability, AND the
 * user has a grant. No grant = no access (deny by default).
 */
@Entity
@Table(
    name = "luke_capability_grants",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_grant_tenant_user_capability",
        columnNames = {"tenantId", "userId", "capabilityCode"}),
    indexes = @Index(name = "idx_grant_tenant_user", columnList = "tenantId,userId")
)
public class CapabilityGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** Engine userId, e.g. {@code clerk:user_2abc}. */
    @Column(nullable = false)
    private String userId;

    /** References {@link com.luke.engine.capability.capability.Capability#getCode()}, e.g. "FORMS". */
    @Column(nullable = false)
    private String capabilityCode;

    /** {@link CapabilityLevel}: read | read-write. */
    @Column(nullable = false)
    private String level;

    private String grantedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public CapabilityGrant() {}

    public CapabilityGrant(String tenantId, String userId, String capabilityCode) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.capabilityCode = capabilityCode;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getCapabilityCode() { return capabilityCode; }
    public void setCapabilityCode(String capabilityCode) { this.capabilityCode = capabilityCode; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getGrantedBy() { return grantedBy; }
    public void setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
