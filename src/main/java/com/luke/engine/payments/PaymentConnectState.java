package com.luke.engine.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A pending Connect OAuth round-trip: the CSRF {@code state} Stripe echoes back, bound to the tenant
 * and the owner who started it. Single-use and short-lived — completing the connection deletes it,
 * and a code arriving with an unknown, expired or someone else's state is refused.
 */
@Entity
@Table(name = "luke_payment_connect_state")
public class PaymentConnectState {

    /** The random state token itself (URL-safe base64, 256 bits). */
    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public PaymentConnectState() {}

    public PaymentConnectState(String id, String tenantId, String userId, LocalDateTime expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}
