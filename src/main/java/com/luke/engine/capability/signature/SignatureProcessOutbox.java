package com.luke.engine.capability.signature;

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
 * Transactional outbox for starting the Camunda process behind a {@link SignatureInstance}
 * (mirrors the forms {@code FormSubmissionOutbox}). The standalone engine has no embedded Camunda,
 * so campaign-start writes a QUEUED row in the SAME transaction as the instance; at merge,
 * luke-core-engine's process service consumes QUEUED rows and starts/correlates the process,
 * stamping back {@code processInstanceId}. The unique {@code businessKey} makes retries idempotent.
 */
@Entity
@Table(
    name = "luke_signature_process_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uq_sigoutbox_businesskey", columnNames = {"businessKey"}),
    indexes = @Index(name = "idx_sigoutbox_state", columnList = "state")
)
public class SignatureProcessOutbox {

    public static final String QUEUED = "QUEUED";   // campaign launched, ceremony not yet started
    public static final String STARTED = "STARTED"; // ceremony process running, awaiting closure
    public static final String CLOSED = "CLOSED";   // instance terminal, closure correlated → done
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Idempotency key (also the Camunda process business key). */
    @Column(nullable = false)
    private String businessKey;

    @Column(nullable = false)
    private String instanceId;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String definitionCode;

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

    public SignatureProcessOutbox() {}

    public SignatureProcessOutbox(String businessKey, String instanceId, String tenantId, String definitionCode) {
        this.businessKey = businessKey;
        this.instanceId = instanceId;
        this.tenantId = tenantId;
        this.definitionCode = definitionCode;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDefinitionCode() { return definitionCode; }
    public void setDefinitionCode(String definitionCode) { this.definitionCode = definitionCode; }
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
