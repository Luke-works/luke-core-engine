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
import jakarta.persistence.Version;
import java.time.LocalDateTime;

/**
 * A SIGNATURE INSTANCE — one occurrence of a published {@link SignatureDefinition} started by a
 * campaign: the live contract bound to its recipients + the data-attribute values they were sent
 * with. Pins {@code definitionCode + definitionVersion} so the document/schema never shifts under
 * it. The state tracks the contract to closure and is kept in step with the Camunda process
 * (started via {@link SignatureProcessOutbox}). Mirrors the forms {@code FormInstance}.
 */
@Entity
@Table(
    name = "luke_signature_instances",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_siginst_token", columnNames = {"token"}),
        @UniqueConstraint(name = "uq_siginst_businesskey", columnNames = {"businessKey"})
    },
    indexes = {
        @Index(name = "idx_siginst_tenant_state", columnList = "tenantId,state"),
        @Index(name = "idx_siginst_def", columnList = "tenantId,definitionCode")
    }
)
public class SignatureInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Version
    private Long version;

    @Column(nullable = false)
    private String tenantId;

    /** Opaque handle for lookup / link delivery. */
    @Column(nullable = false)
    private String token;

    /** References {@link SignatureDefinition#getCode()} (stable external id). */
    @Column(nullable = false)
    private String definitionCode;

    /** Pinned {@link SignatureVersion#getVersion()} — the immutable schema this contract uses. */
    @Column(nullable = false)
    private int definitionVersion;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 32)
    private String state = SignatureInstanceStates.CREATED;

    /** Provided data-attribute values ({{key}} → value) as JSON. */
    @Column(columnDefinition = "text")
    private String valuesJson;

    /** Camunda process binding (set when the outbox consumer starts the process). */
    private String businessKey;
    private String processInstanceId;
    @Column(length = 32)
    private String processStatus;

    /** Final sealed PDF (DocumentStore key + integrity hash) + seal outcome, set at closure. */
    private String signedObjectKey;
    private String signedSha256;
    @Column(length = 16)
    private String sealStatus; // null until closure → SEALED | FAILED
    @Column(columnDefinition = "text")
    private String sealError;

    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private LocalDateTime expiresAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getDefinitionCode() { return definitionCode; }
    public void setDefinitionCode(String definitionCode) { this.definitionCode = definitionCode; }
    public int getDefinitionVersion() { return definitionVersion; }
    public void setDefinitionVersion(int definitionVersion) { this.definitionVersion = definitionVersion; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getValuesJson() { return valuesJson; }
    public void setValuesJson(String valuesJson) { this.valuesJson = valuesJson; }
    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getProcessStatus() { return processStatus; }
    public void setProcessStatus(String processStatus) { this.processStatus = processStatus; }
    public String getSignedObjectKey() { return signedObjectKey; }
    public void setSignedObjectKey(String signedObjectKey) { this.signedObjectKey = signedObjectKey; }
    public String getSignedSha256() { return signedSha256; }
    public void setSignedSha256(String signedSha256) { this.signedSha256 = signedSha256; }
    public String getSealStatus() { return sealStatus; }
    public void setSealStatus(String sealStatus) { this.sealStatus = sealStatus; }
    public String getSealError() { return sealError; }
    public void setSealError(String sealError) { this.sealError = sealError; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
