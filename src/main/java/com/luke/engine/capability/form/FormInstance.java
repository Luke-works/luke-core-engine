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

    /** Denormalised, indexed recipient email (lower-cased) — kept in sync by {@link #setRecipient}
     *  so the portal can list every open instance for a recipient without parsing the JSON blob. */
    @Column(length = 320)
    private String recipientEmail;

    /** Denormalised recipient phone (for the SMS portal channel), kept in sync by {@link #setRecipient}. */
    @Column(length = 40)
    private String recipientPhone;

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

    /** Submission provenance — the evidence a completed form needs to be enforceable. Written ONCE, at
     *  submit, by {@link FormSubmissionService} from a {@link SubmissionSource} captured at the request
     *  edge; never rewritten afterwards (snapshot semantics, like the attachment audit). Also copied
     *  into the immutable {@code formMetaData} the process instance carries.
     *
     *  <p>{@code submittedIp} is the observed client IP (45 chars fits IPv6), {@code submittedVia} is
     *  which door it came through (EMBED / RESPOND / APP). Personal data — see {@link SubmissionSource}. */
    @Column(length = 45)
    private String submittedIp;

    @Column(length = 512)
    private String submittedUserAgent;

    @Column(length = 32)
    private String submittedVia;

    /** The consent record: the EXACT statement the filler agreed to, and when they agreed. Resolved
     *  server-side from the schema of the version the instance is pinned to ({@link ConsentTerms}) — the
     *  request only ever carries the filler's tick — and written once, alongside the rest of the
     *  provenance. Null on instances whose form did not require consent.
     *
     *  <p>This is the part that makes a submission provable: an IP says where a packet came from, this
     *  says what the person accepted. Copied into {@code formMetaData} and printed on the submission PDF
     *  so the evidence outlives the row. */
    @Column(columnDefinition = "text")
    private String consentText;

    private LocalDateTime consentAgreedAt;

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

    /** Sets the recipient JSON AND derives the denormalised {@code recipientEmail}/{@code recipientPhone}
     *  columns, so the two never drift apart no matter which path creates the instance. */
    public void setRecipient(Map<String, Object> recipient) {
        this.recipient = recipient;
        this.recipientEmail = normalizeEmail(recipient == null ? null : recipient.get("email"));
        this.recipientPhone = trimToNull(recipient == null ? null : recipient.get("phone"));
    }

    public String getRecipientEmail() { return recipientEmail; }

    public String getRecipientPhone() { return recipientPhone; }

    private static String normalizeEmail(Object v) {
        String s = trimToNull(v);
        return s == null ? null : s.toLowerCase();
    }

    private static String trimToNull(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    /** True once the instance's expiry has passed (#54). Enforced on save/submit and
     *  swept by FormInstanceExpirySweeper. */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getSubmittedIp() { return submittedIp; }
    public void setSubmittedIp(String submittedIp) { this.submittedIp = submittedIp; }

    public String getSubmittedUserAgent() { return submittedUserAgent; }
    public void setSubmittedUserAgent(String submittedUserAgent) { this.submittedUserAgent = submittedUserAgent; }

    public String getSubmittedVia() { return submittedVia; }
    public void setSubmittedVia(String submittedVia) { this.submittedVia = submittedVia; }

    public String getConsentText() { return consentText; }
    public void setConsentText(String consentText) { this.consentText = consentText; }

    public LocalDateTime getConsentAgreedAt() { return consentAgreedAt; }
    public void setConsentAgreedAt(LocalDateTime consentAgreedAt) { this.consentAgreedAt = consentAgreedAt; }
}
