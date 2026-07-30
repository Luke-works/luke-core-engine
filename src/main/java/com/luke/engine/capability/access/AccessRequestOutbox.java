package com.luke.engine.capability.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Transactional outbox for starting the access-request approval process.
 *
 * <p>The request row and this row are written in ONE transaction, so a member's request is never
 * accepted without something durable that will eventually start the workflow — and the HTTP call
 * never depends on the process engine being up. {@link AccessRequestOutboxConsumer} drains it.
 *
 * <p>{@code accessRequestId} is unique: at most one process start per request, however many times
 * the consumer retries. Mirrors {@code FormSubmissionOutbox}.
 */
@Entity
@Table(name = "luke_access_request_outbox",
        indexes = { @Index(name = "idx_access_outbox_status", columnList = "status") })
public class AccessRequestOutbox {

    public static final String QUEUED = "QUEUED";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** The request this start belongs to — unique, so a retry can never start a second process. */
    @Column(nullable = false, unique = true)
    private String accessRequestId;

    /** Camunda business key for the instance (also the idempotency key on the start itself). */
    @Column(nullable = false)
    private String businessKey;

    @Column(nullable = false)
    private String status = QUEUED;

    private String processInstanceId;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant publishedAt;

    public AccessRequestOutbox() {}

    public AccessRequestOutbox(String tenantId, String accessRequestId, String businessKey) {
        this.tenantId = tenantId;
        this.accessRequestId = accessRequestId;
        this.businessKey = businessKey;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getAccessRequestId() { return accessRequestId; }
    public void setAccessRequestId(String accessRequestId) { this.accessRequestId = accessRequestId; }

    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
}
