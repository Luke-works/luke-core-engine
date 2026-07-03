package com.luke.engine.capability.capability;

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
 * Per-tenant enablement of a {@link Capability}. A tenant "has" a capability
 * when there is an ACTIVE subscription row for it. The capability itself is a
 * global catalog entry; this table is the tenant-scoped on/off switch.
 */
@Entity
@Table(
    name = "luke_capability_subscriptions",
    uniqueConstraints = @UniqueConstraint(name = "uq_tenant_capability", columnNames = {"tenantId", "capabilityCode"}),
    indexes = @Index(name = "idx_subscription_tenant", columnList = "tenantId")
)
public class CapabilitySubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** References {@link Capability#getCode()}. */
    @Column(nullable = false)
    private String capabilityCode;

    /** Subscription lifecycle: ACTIVE, SUSPENDED. */
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public CapabilitySubscription() {}

    public CapabilitySubscription(String tenantId, String capabilityCode) {
        this.tenantId = tenantId;
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

    public String getCapabilityCode() { return capabilityCode; }
    public void setCapabilityCode(String capabilityCode) { this.capabilityCode = capabilityCode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
