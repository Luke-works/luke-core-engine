package com.luke.engine.capability.phone;

import com.luke.engine.capability.form.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.Map;

/**
 * A single Vapi phone call kept as an audit row — inbound or outbound — and the
 * record a Camunda process is wrapped around. Outbound calls are placed through the
 * Vapi REST API and start QUEUED→RINGING; inbound calls are observed from the
 * {@code assistant-request} webhook. Either way, Vapi's {@code status-update} and
 * {@code end-of-call-report} webhooks advance the row to its terminal state and
 * stamp the transcript, recording, summary, cost, and any structured analysis.
 *
 * <p>Lives in core's currentSchema (Strategy A); tenant isolation is by the explicit
 * {@code tenantId} column + tenant-scoped repository queries. The {@code vapiCallId}
 * is Vapi's id for the call and the join key for incoming webhooks.
 */
@Entity
@Table(
    name = "luke_phone_calls",
    uniqueConstraints = @UniqueConstraint(name = "uq_phonecall_vapi", columnNames = {"vapiCallId"}),
    indexes = {
        @Index(name = "idx_phonecall_tenant", columnList = "tenantId"),
        @Index(name = "idx_phonecall_tenant_status", columnList = "tenantId,status"),
        @Index(name = "idx_phonecall_vapi", columnList = "vapiCallId")
    }
)
public class PhoneCall {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Version
    private Long version;

    @Column(nullable = false)
    private String tenantId;

    /** INBOUND or OUTBOUND (see {@link PhoneCallDirection}). */
    @Column(nullable = false, length = 16)
    private String direction;

    /** Lifecycle: QUEUED, RINGING, IN_PROGRESS, ENDED, FAILED (see {@link PhoneCallStatus}). */
    @Column(nullable = false, length = 24)
    private String status = PhoneCallStatus.QUEUED;

    /** Vapi's id for this call — set once the call is created/observed. */
    private String vapiCallId;

    /** The Vapi phone-number id this call is placed from / arrived on. */
    private String phoneNumberId;

    /** The Vapi assistant id answering / placing the call. */
    private String assistantId;

    /** The other party's number in E.164 (callee for outbound, caller for inbound). */
    private String customerNumber;

    /** Vapi's machine-readable end reason (e.g. customer-ended-call, assistant-error). */
    private String endedReason;

    /** Full call transcript delivered with the end-of-call report. */
    @Column(columnDefinition = "text")
    private String transcript;

    /** Vapi recording URL (when recording is enabled on the assistant). */
    @Column(length = 1000)
    private String recordingUrl;

    /** Assistant-generated call summary from the end-of-call report. */
    @Column(columnDefinition = "text")
    private String summary;

    /** Total call cost in USD as reported by Vapi. */
    private Double cost;

    /** Structured outputs / success evaluation from Vapi's analysis plan. */
    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> analysis;

    /** Caller-supplied linkage and dynamic variables (process/form ids, names). */
    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> metadata;

    /** Set when an outbound placement failed before Vapi accepted it. */
    @Column(length = 2000)
    private String errorMessage;

    /* ── Camunda binding (mirrors SignatureInstance) ───────────── */

    /** Camunda business key = this row's id (drives the outbox + process). */
    private String businessKey;

    private String processInstanceId;

    /** QUEUED → STARTED → CLOSED (see {@link PhoneCallProcessOutbox}). */
    @Column(length = 16)
    private String processStatus;

    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public PhoneCall() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getVapiCallId() { return vapiCallId; }
    public void setVapiCallId(String vapiCallId) { this.vapiCallId = vapiCallId; }

    public String getPhoneNumberId() { return phoneNumberId; }
    public void setPhoneNumberId(String phoneNumberId) { this.phoneNumberId = phoneNumberId; }

    public String getAssistantId() { return assistantId; }
    public void setAssistantId(String assistantId) { this.assistantId = assistantId; }

    public String getCustomerNumber() { return customerNumber; }
    public void setCustomerNumber(String customerNumber) { this.customerNumber = customerNumber; }

    public String getEndedReason() { return endedReason; }
    public void setEndedReason(String endedReason) { this.endedReason = endedReason; }

    public String getTranscript() { return transcript; }
    public void setTranscript(String transcript) { this.transcript = transcript; }

    public String getRecordingUrl() { return recordingUrl; }
    public void setRecordingUrl(String recordingUrl) { this.recordingUrl = recordingUrl; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Double getCost() { return cost; }
    public void setCost(Double cost) { this.cost = cost; }

    public Map<String, Object> getAnalysis() { return analysis; }
    public void setAnalysis(Map<String, Object> analysis) { this.analysis = analysis; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }

    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }

    public String getProcessStatus() { return processStatus; }
    public void setProcessStatus(String processStatus) { this.processStatus = processStatus; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
