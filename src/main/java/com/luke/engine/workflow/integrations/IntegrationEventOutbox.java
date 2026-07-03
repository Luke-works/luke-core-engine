package com.luke.engine.workflow.integrations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Transactional outbox for the inbound rail (WF-12), mirroring {@code PhoneCallProcessOutbox}.
 * A Nango sync/forward webhook is written here as a QUEUED row in the same transaction as the
 * dedup ledger; {@link IntegrationEventOutboxConsumer} then correlates a Camunda message —
 * starting a workflow (message start) or advancing one waiting at a message catch.
 *
 * <p>Idempotency is upstream: the webhook is deduped by {@link IntegrationWebhookLog} before a
 * row is ever enqueued, so at-least-once delivery never double-enqueues.
 */
@Entity
@Table(
    name = "luke_integration_event_outbox",
    indexes = @Index(name = "idx_intevent_state", columnList = "state")
)
public class IntegrationEventOutbox {

    public static final String QUEUED = "QUEUED";   // received, not yet correlated
    public static final String SENT = "SENT";       // correlated to >=1 execution/instance
    public static final String SKIPPED = "SKIPPED"; // no waiting instance — nowhere to deliver
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** The BPMN message name to correlate, e.g. {@code "integrations.salesforce_opportunities"}. */
    @Column(nullable = false)
    private String messageName;

    /** Optional business key threading (advance a specific instance); null → correlate all by name. */
    private String correlationKey;

    private String connectionId;

    private String eventType;

    @Column(columnDefinition = "text")
    private String payloadJson;

    @Column(nullable = false)
    private String state = QUEUED;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public IntegrationEventOutbox() {}

    public IntegrationEventOutbox(String tenantId, String messageName, String correlationKey,
            String connectionId, String eventType, String payloadJson) {
        this.tenantId = tenantId;
        this.messageName = messageName;
        this.correlationKey = correlationKey;
        this.connectionId = connectionId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getMessageName() { return messageName; }
    public String getCorrelationKey() { return correlationKey; }
    public String getConnectionId() { return connectionId; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
