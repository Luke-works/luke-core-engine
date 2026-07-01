package com.luke.engine.capability.phone;

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
 * Transactional outbox for starting the Camunda process behind a {@link PhoneCall}
 * (mirrors {@code SignatureProcessOutbox}). When a call is placed (outbound) or first
 * observed (inbound), a QUEUED row is written in the SAME transaction as the call; the
 * {@link PhoneCallProcessOutboxConsumer} starts the {@code PhoneCallProcess} (it parks
 * at "Await Call End") and, once the call reaches a terminal status, correlates the
 * {@code PhoneCallEnded} message to finish it. The unique {@code businessKey} (= the
 * call id) makes retries idempotent.
 */
@Entity
@Table(
    name = "luke_phone_call_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uq_phoneoutbox_businesskey", columnNames = {"businessKey"}),
    indexes = @Index(name = "idx_phoneoutbox_state", columnList = "state")
)
public class PhoneCallProcessOutbox {

    public static final String QUEUED = "QUEUED";   // call created, process not yet started
    public static final String STARTED = "STARTED"; // process running, awaiting call end
    public static final String CLOSED = "CLOSED";   // call terminal, end correlated → done
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Idempotency key (also the Camunda process business key) = the call id. */
    @Column(nullable = false)
    private String businessKey;

    @Column(nullable = false)
    private String callId;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false, length = 16)
    private String direction;

    @Column(nullable = false)
    private String state = QUEUED;

    private String processInstanceId;

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

    public PhoneCallProcessOutbox() {}

    public PhoneCallProcessOutbox(String businessKey, String callId, String tenantId, String direction) {
        this.businessKey = businessKey;
        this.callId = callId;
        this.tenantId = tenantId;
        this.direction = direction;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
