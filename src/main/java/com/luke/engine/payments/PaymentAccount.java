package com.luke.engine.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A tenant's connected Stripe account — one row per tenant ({@code id} IS the tenantId).
 *
 * <p>Holds only the account id and what we last read about it. No Stripe credential of the tenant's
 * is ever stored: direct charges authenticate with the PLATFORM key plus a {@code Stripe-Account}
 * header, so there is nothing of theirs to leak.
 */
@Entity
@Table(name = "luke_payment_account", indexes = {
        @Index(name = "idx_payment_account_stripe", columnList = "stripe_account_id")
})
public class PaymentAccount {

    public static final String CONNECTED = "CONNECTED";
    public static final String DISCONNECTED = "DISCONNECTED";
    /** A disconnect is closing the account's open charges; it takes no new ones meanwhile. */
    public static final String DISCONNECTING = "DISCONNECTING";

    @Id
    private String id;

    @Column(name = "stripe_account_id", nullable = false)
    private String stripeAccountId;

    @Column(nullable = false)
    private String status = CONNECTED;

    @Column(nullable = false)
    private boolean livemode;

    @Column(nullable = false)
    private boolean chargesEnabled;

    @Column(nullable = false)
    private boolean detailsSubmitted;

    private String displayName;

    @Column(length = 8)
    private String defaultCurrency;

    @Column(length = 8)
    private String country;

    private String connectedBy;

    private LocalDateTime connectedAt;

    private LocalDateTime disconnectedAt;

    private LocalDateTime lastSyncedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** Connected, able to take charges, and in the platform's mode. */
    public boolean isReady(boolean platformLivemode) {
        return CONNECTED.equals(status) && chargesEnabled && livemode == platformLivemode;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStripeAccountId() { return stripeAccountId; }
    public void setStripeAccountId(String stripeAccountId) { this.stripeAccountId = stripeAccountId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean livemode) { this.livemode = livemode; }
    public boolean isChargesEnabled() { return chargesEnabled; }
    public void setChargesEnabled(boolean chargesEnabled) { this.chargesEnabled = chargesEnabled; }
    public boolean isDetailsSubmitted() { return detailsSubmitted; }
    public void setDetailsSubmitted(boolean detailsSubmitted) { this.detailsSubmitted = detailsSubmitted; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDefaultCurrency() { return defaultCurrency; }
    public void setDefaultCurrency(String defaultCurrency) { this.defaultCurrency = defaultCurrency; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getConnectedBy() { return connectedBy; }
    public void setConnectedBy(String connectedBy) { this.connectedBy = connectedBy; }
    public LocalDateTime getConnectedAt() { return connectedAt; }
    public void setConnectedAt(LocalDateTime connectedAt) { this.connectedAt = connectedAt; }
    public LocalDateTime getDisconnectedAt() { return disconnectedAt; }
    public void setDisconnectedAt(LocalDateTime disconnectedAt) { this.disconnectedAt = disconnectedAt; }
    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(LocalDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
