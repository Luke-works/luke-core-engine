package com.luke.engine.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A Connect webhook event already applied — Stripe delivers at-least-once, so the event id is the
 * dedupe key. Written only AFTER the event is handled, so a failed handling is retried.
 */
@Entity
@Table(name = "luke_payment_webhook_event")
public class PaymentWebhookEvent {

    @Id
    private String id;

    @Column(nullable = false)
    private String type;

    private String accountId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();

    public PaymentWebhookEvent() {}

    public PaymentWebhookEvent(String id, String type, String accountId) {
        this.id = id;
        this.type = type;
        this.accountId = accountId;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public String getAccountId() { return accountId; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
}
