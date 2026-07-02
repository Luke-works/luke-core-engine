package com.luke.engine.capability.form;

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
 * Transactional outbox for the forms→workflow inbound rail. When a form instance changes
 * lifecycle state (submitted, processed, …), a QUEUED row is written here in the SAME
 * transaction as the state change, so the emit intent can never be lost. The
 * {@code FormEventConsumer} then drains it and hands each event to the
 * {@code FormEventCorrelator}, which starts subscribed workflows and advances any waiting
 * at a matching message catch.
 *
 * <p>Mirrors {@code IntegrationEventOutbox} (the Nango inbound rail); the difference is that
 * a form event carries the {@code eventType} + {@code formCode} explicitly (the correlator
 * derives message names / matches subscriptions from them) rather than a pre-baked message
 * name.
 */
@Entity
@Table(
    name = "luke_form_event_outbox",
    indexes = @Index(name = "idx_formevent_state", columnList = "state")
)
public class FormEventOutbox {

    public static final String QUEUED = "QUEUED";   // written, not yet correlated
    public static final String SENT = "SENT";       // started/advanced >=1 workflow
    public static final String SKIPPED = "SKIPPED"; // no subscriber/waiter — nowhere to deliver
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** The lifecycle event, lower-cased from the FormInstance state, e.g. {@code "submitted"}. */
    @Column(nullable = false)
    private String eventType;

    /** The form definition code the instance belongs to; scopes which workflows fire. */
    private String formCode;

    private String instanceId;

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

    public FormEventOutbox() {}

    public FormEventOutbox(String tenantId, String eventType, String formCode, String instanceId, String payloadJson) {
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.formCode = formCode;
        this.instanceId = instanceId;
        this.payloadJson = payloadJson;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEventType() { return eventType; }
    public String getFormCode() { return formCode; }
    public String getInstanceId() { return instanceId; }
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
