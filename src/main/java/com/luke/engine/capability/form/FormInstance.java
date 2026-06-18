package com.luke.engine.capability.form;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * A concrete runtime occurrence of a {@link FormDefinition} version: a blank or
 * prefilled form with answers and a lifecycle state. Covers the public hosted
 * submission, a prefilled invitation sent to a recipient, and a task-bound fill.
 *
 * <p>Pins {@code definitionCode}+{@code version} so the form it renders never
 * shifts under it. {@code context} links back to a process instance/task when
 * one drives it.
 */
@Entity
@Table(
    name = "luke_form_instances",
    indexes = {
        @Index(name = "idx_forminstance_tenant", columnList = "tenantId"),
        @Index(name = "idx_forminstance_token", columnList = "token"),
        @Index(name = "idx_forminstance_def", columnList = "definitionCode")
    }
)
public class FormInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** Opaque handle for link/URL delivery (invitations, hosted forms). */
    @Column(nullable = false, unique = true)
    private String token;

    /** References {@link FormDefinition#getCode()}. */
    @Column(nullable = false)
    private String definitionCode;

    /** The pinned definition version this instance renders. */
    @Column(nullable = false)
    private int version;

    /** Lifecycle: CREATED, SENT, OPENED, IN_PROGRESS, SUBMITTED, PROCESSED, EXPIRED, CANCELLED. */
    @Column(nullable = false)
    private String state = FormInstanceStates.CREATED;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> prefill;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> data;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> recipient;

    /** {@code { processInstanceId, taskId, businessKey, correlation:{messageName} }}. */
    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> context;

    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime expiresAt;
    private LocalDateTime submittedAt;
    private LocalDateTime updatedAt;

    public FormInstance() {}

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getDefinitionCode() { return definitionCode; }
    public void setDefinitionCode(String definitionCode) { this.definitionCode = definitionCode; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Map<String, Object> getPrefill() { return prefill; }
    public void setPrefill(Map<String, Object> prefill) { this.prefill = prefill; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public Map<String, Object> getRecipient() { return recipient; }
    public void setRecipient(Map<String, Object> recipient) { this.recipient = recipient; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
