package com.luke.engine.workflow.integrations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Idempotency ledger for inbound Nango webhooks — Nango delivers at-least-once, so we
 * record each processed delivery and skip repeats. The {@code deliveryId} is the
 * webhook signature when present, else a hash of the raw body (identical payloads
 * dedupe). Not tenant-scoped: the row correlates the webhook to a tenant, not the log.
 */
@Entity
@Table(name = "luke_integration_webhook_log")
public class IntegrationWebhookLog {

    @Id
    @Column(length = 128)
    private String deliveryId;

    private String type;

    private boolean signatureOk;

    @Column(nullable = false, updatable = false)
    private LocalDateTime processedAt = LocalDateTime.now();

    public IntegrationWebhookLog() {}

    public IntegrationWebhookLog(String deliveryId, String type, boolean signatureOk) {
        this.deliveryId = deliveryId;
        this.type = type;
        this.signatureOk = signatureOk;
    }

    public String getDeliveryId() { return deliveryId; }
    public void setDeliveryId(String deliveryId) { this.deliveryId = deliveryId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isSignatureOk() { return signatureOk; }
    public void setSignatureOk(boolean signatureOk) { this.signatureOk = signatureOk; }

    public LocalDateTime getProcessedAt() { return processedAt; }
}
