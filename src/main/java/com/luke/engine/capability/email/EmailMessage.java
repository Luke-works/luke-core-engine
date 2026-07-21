package com.luke.engine.capability.email;

import com.luke.engine.capability.form.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * A single transactional email handed to Postmark, kept as an audit row: who it
 * went to, what was sent, and the outcome Postmark reported (MessageID on success,
 * ErrorCode + message on failure). Both the tenant-facing API and the internal
 * process-triggered endpoint write these, so every send is traceable and listable.
 *
 * <p>Lives in the capability engine's own schema (see hibernate.default_schema).
 * Recipient lists are stored as Postmark expects them on the wire — a single
 * comma-separated string per field.
 */
@Entity
@Table(
    name = "luke_email_messages",
    indexes = {
        @Index(name = "idx_email_tenant", columnList = "tenantId"),
        @Index(name = "idx_email_status", columnList = "status"),
        @Index(name = "idx_email_postmark", columnList = "postmarkMessageId")
    }
)
public class EmailMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** Lifecycle: QUEUED, SENT, FAILED (see {@link EmailStatus}). */
    @Column(nullable = false)
    private String status = EmailStatus.QUEUED;

    /** Resolved sender (request value, or the platform default fallback). */
    @Column(nullable = false)
    private String fromAddress;

    /** Comma-separated recipients, as Postmark accepts them. */
    @Column(nullable = false, length = 1000)
    private String toAddress;

    @Column(length = 1000)
    private String cc;

    @Column(length = 1000)
    private String bcc;

    private String replyTo;

    @Column(length = 1000)
    private String subject;

    /** Postmark template id (numeric) when this was a template send. */
    private Long templateId;

    /** Postmark template alias when this was a template send. */
    private String templateAlias;

    /** Postmark message stream, e.g. "outbound" / "broadcast". */
    private String messageStream;

    /** Free-form categorization tag for Postmark analytics. */
    private String tag;

    /** Postmark TemplateModel (template sends) or custom Metadata (raw sends). */
    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> model;

    /** Caller-supplied linkage (e.g. processInstanceId, formInstanceId). */
    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> context;

    /** Postmark MessageID returned on a successful submission. */
    private String postmarkMessageId;

    /** Postmark ErrorCode (0 = success); set on failure. */
    private Integer errorCode;

    @Column(length = 2000)
    private String errorMessage;

    private String createdBy;

    /** OUTBOUND (sent by us) or INBOUND (received via the public inbound webhook). */
    @Column(nullable = false)
    private String direction = "OUTBOUND";

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime sentAt;

    public EmailMessage() {}

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }

    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }

    public String getCc() { return cc; }
    public void setCc(String cc) { this.cc = cc; }

    public String getBcc() { return bcc; }
    public void setBcc(String bcc) { this.bcc = bcc; }

    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String replyTo) { this.replyTo = replyTo; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }

    public String getTemplateAlias() { return templateAlias; }
    public void setTemplateAlias(String templateAlias) { this.templateAlias = templateAlias; }

    public String getMessageStream() { return messageStream; }
    public void setMessageStream(String messageStream) { this.messageStream = messageStream; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public Map<String, Object> getModel() { return model; }
    public void setModel(Map<String, Object> model) { this.model = model; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }

    public String getPostmarkMessageId() { return postmarkMessageId; }
    public void setPostmarkMessageId(String postmarkMessageId) { this.postmarkMessageId = postmarkMessageId; }

    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
}
