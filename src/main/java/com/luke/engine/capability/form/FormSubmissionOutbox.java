package com.luke.engine.capability.form;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Transactional outbox for form-submission → process-start. A submission writes the
 * form instance state AND a QUEUED row here in ONE transaction; the
 * {@link FormSubmissionOutboxConsumer} later starts the Camunda process and flips
 * the row to PUBLISHED/FAILED. A crash between the two never loses the start intent.
 *
 * <p>{@code businessKey} (= the form instance id) is unique → at most one start per
 * submission (idempotency); a retry resets the existing row rather than adding one.
 */
@Entity
@Table(name = "luke_form_submission_outbox",
        indexes = { @Index(name = "idx_outbox_status", columnList = "status") })
public class FormSubmissionOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** = form instance id. Unique: one start per submission (idempotency). */
    @Column(nullable = false, unique = true)
    private String businessKey;

    @Column(nullable = false)
    private String formInstanceId;

    /** Human-readable Camunda process business key (SM-&lt;7 alnum&gt;-YYYYMMMDD),
     *  generated once per submission. Distinct from {@code businessKey} above, which
     *  is the outbox idempotency key (= the form instance id). */
    private String processBusinessKey;

    @Column(columnDefinition = "text")
    private String formDataJson;

    @Column(columnDefinition = "text")
    private String formMetaJson;

    /** QUEUED | PUBLISHED | FAILED */
    @Column(nullable = false)
    private String status = "QUEUED";

    private String processInstanceId;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant publishedAt;

    public FormSubmissionOutbox() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }

    public String getFormInstanceId() { return formInstanceId; }
    public void setFormInstanceId(String formInstanceId) { this.formInstanceId = formInstanceId; }

    public String getProcessBusinessKey() { return processBusinessKey; }
    public void setProcessBusinessKey(String processBusinessKey) { this.processBusinessKey = processBusinessKey; }

    public String getFormDataJson() { return formDataJson; }
    public void setFormDataJson(String formDataJson) { this.formDataJson = formDataJson; }

    public String getFormMetaJson() { return formMetaJson; }
    public void setFormMetaJson(String formMetaJson) { this.formMetaJson = formMetaJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
}
