package com.luke.engine.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * The Stripe ↔ tenant mapping — one row per tenant ({@code id} IS the tenantId), created the first
 * time a tenant completes checkout. It lets a later {@code customer.subscription.*} webhook (which
 * carries a Stripe customer id, not a tenant) be resolved back to the tenant, and lets a returning
 * customer reuse their Stripe Customer rather than minting a duplicate.
 *
 * <p>This is a convenience/reconciliation record, not the source of truth for what a tenant pays —
 * that stays {@link com.luke.engine.branding.TenantPlan}. The absence of a row simply means the tenant
 * has never transacted through Stripe.
 */
@Entity
@Table(name = "luke_billing_customer")
public class BillingCustomer {

    /** The tenantId — one billing-customer row per tenant. */
    @Id
    private String id;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public BillingCustomer() {}

    public BillingCustomer(String tenantId, String stripeCustomerId) {
        this.id = tenantId;
        this.stripeCustomerId = stripeCustomerId;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }

    public String getStripeSubscriptionId() { return stripeSubscriptionId; }
    public void setStripeSubscriptionId(String stripeSubscriptionId) { this.stripeSubscriptionId = stripeSubscriptionId; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
