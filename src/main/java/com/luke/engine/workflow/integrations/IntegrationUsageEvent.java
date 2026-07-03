package com.luke.engine.workflow.integrations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * A metered usage event for the WORKFLOW capability (the emit site; WF-15 forwards these
 * to Stripe). {@code idempotencyKey} makes the emit exactly-once — a retried connector
 * execution reuses the same key, so it's counted once. {@code billedAt} is set when the
 * event has been reported to the meter.
 */
@Entity
@Table(
    name = "luke_integration_usage_events",
    uniqueConstraints = @UniqueConstraint(name = "uq_usage_idempotency", columnNames = "idempotencyKey"),
    indexes = @Index(name = "idx_usage_tenant", columnList = "tenantId")
)
public class IntegrationUsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    private String connectionId;

    /** {@code execution} (a connector action ran) — later also {@code active_connection}, {@code sync_record}. */
    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private int quantity = 1;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    private LocalDateTime billedAt;

    public IntegrationUsageEvent() {}

    public IntegrationUsageEvent(String tenantId, String connectionId, String type, String idempotencyKey) {
        this.tenantId = tenantId;
        this.connectionId = connectionId;
        this.type = type;
        this.idempotencyKey = idempotencyKey;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getConnectionId() { return connectionId; }
    public String getType() { return type; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public LocalDateTime getBilledAt() { return billedAt; }
    public void setBilledAt(LocalDateTime billedAt) { this.billedAt = billedAt; }
}
