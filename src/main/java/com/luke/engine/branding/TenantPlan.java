package com.luke.engine.branding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Per-tenant commercial plan — the billing seam the platform reads when a feature is only available
 * to PAYING customers. One row per tenant ({@code id} IS the tenantId), mirroring
 * {@link com.luke.engine.capability.phone.PhoneSettings}.
 *
 * <p>There is deliberately no row for most tenants: <b>absent = {@link #PLAN_FREE}</b>. A tenant only
 * gets a row once an operator (or, later, the billing integration) records that it pays. That makes
 * the free tier the fail-closed default — a lost/rolled-back row can never silently upgrade someone.
 *
 * <p>Today the only consumer is {@link BrandingPolicy} (who may hide the "Developed at Lukeflow"
 * badge). It is intentionally NOT a capability-catalog {@code tier}: {@link
 * com.luke.engine.capability.capability.Capability#getTier()} describes a MODULE's pricing band,
 * while this describes a TENANT's plan. When real billing lands it writes this table and nothing
 * downstream changes.
 */
@Entity
@Table(name = "luke_tenant_plan")
public class TenantPlan {

    /** No row / no charge: the tenant is on the free tier. */
    public static final String PLAN_FREE = "FREE";
    /** The tenant pays — paid-only options (e.g. hiding the badge) unlock. */
    public static final String PLAN_PAID = "PAID";

    /** The tenantId — one plan row per tenant. */
    @Id
    private String id;

    /** {@link #PLAN_FREE} or {@link #PLAN_PAID}. */
    @Column(nullable = false)
    private String plan = PLAN_FREE;

    /** Free-text operator note (who authorized the change / contract reference). */
    private String note;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public TenantPlan() {}

    public TenantPlan(String tenantId, String plan) {
        this.id = tenantId;
        this.plan = plan;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** True when this row marks the tenant as paying (any tier above FREE — resolved via the catalog). */
    public boolean isPaid() {
        return PlanCatalog.fromStored(plan).isPaid();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
