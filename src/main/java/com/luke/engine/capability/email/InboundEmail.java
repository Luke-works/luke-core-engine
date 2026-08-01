package com.luke.engine.capability.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * The content of a received email — the half of an inbound message that
 * {@link EmailMessage} deliberately does not carry.
 *
 * <p>An inbound message is stored as an {@link EmailMessage} row (direction {@code INBOUND}) for
 * the envelope — who, to whom, subject, status — and one row here, sharing that row's id, for
 * what was actually written. The split is deliberate: {@code luke_email_messages} is the hot
 * list/pagination table for the whole EMAIL capability and is queried on every inbox render,
 * while bodies are large, inbound-only, and read one message at a time.
 *
 * <p><b>Attachments are metadata only</b> — name, content type, byte length. Postmark inlines
 * attachment bytes as base64 in the webhook payload; persisting those here (or passing them into
 * a process variable) would put multi-megabyte blobs in a row and in the Camunda variable table,
 * where they would be copied on every process migration. File bytes belong in object storage
 * (the DOCUMENTS capability); this records that they arrived and what they were.
 */
@Entity
@Table(
    name = "luke_email_inbound",
    indexes = {
        @Index(name = "idx_emailinbound_tenant", columnList = "tenantId"),
        @Index(name = "idx_emailinbound_box", columnList = "tenantId,boxId")
    }
)
public class InboundEmail {

    /** Shared primary key: the {@link EmailMessage} id this content belongs to. */
    @Id
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** The matched INBOUND {@link EmailBox}, or null when nothing matched (still stored). */
    private String boxId;

    private String boxAddress;

    /** Postmark MailboxHash — the {@code +hash} used to route without MX. */
    private String mailboxHash;

    /** The sender's display name, when the mailer supplied one. */
    private String fromName;

    @Column(length = 1000)
    private String toFull;

    @Column(length = 1000)
    private String ccAddresses;

    private String replyTo;

    @Column(columnDefinition = "text")
    private String textBody;

    @Column(columnDefinition = "text")
    private String htmlBody;

    /** Postmark's best guess at the new text only, with the quoted thread removed. */
    @Column(columnDefinition = "text")
    private String strippedTextReply;

    /**
     * RFC 5322 {@code Message-ID} and {@code In-Reply-To}. Sized to 998 — the RFC 5322 maximum
     * line length, and therefore the bound on these headers. The usual varchar(255) silently
     * truncates the long ids some mailers generate, which would break reply threading.
     */
    @Column(length = 998)
    private String messageIdHeader;

    @Column(length = 998)
    private String inReplyTo;

    /** JSON array: {@code [{"name":…,"contentType":…,"contentLength":…}]}. */
    @Column(columnDefinition = "text")
    private String attachments;

    /** JSON array of the raw headers: {@code [{"name":…,"value":…}]}. */
    @Column(columnDefinition = "text")
    private String headers;

    @Column(nullable = false)
    private int attachmentCount;

    @Column(nullable = false, updatable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();

    public InboundEmail() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getBoxId() { return boxId; }
    public void setBoxId(String boxId) { this.boxId = boxId; }

    public String getBoxAddress() { return boxAddress; }
    public void setBoxAddress(String boxAddress) { this.boxAddress = boxAddress; }

    public String getMailboxHash() { return mailboxHash; }
    public void setMailboxHash(String mailboxHash) { this.mailboxHash = mailboxHash; }

    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }

    public String getToFull() { return toFull; }
    public void setToFull(String toFull) { this.toFull = toFull; }

    public String getCcAddresses() { return ccAddresses; }
    public void setCcAddresses(String ccAddresses) { this.ccAddresses = ccAddresses; }

    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String replyTo) { this.replyTo = replyTo; }

    public String getTextBody() { return textBody; }
    public void setTextBody(String textBody) { this.textBody = textBody; }

    public String getHtmlBody() { return htmlBody; }
    public void setHtmlBody(String htmlBody) { this.htmlBody = htmlBody; }

    public String getStrippedTextReply() { return strippedTextReply; }
    public void setStrippedTextReply(String strippedTextReply) { this.strippedTextReply = strippedTextReply; }

    public String getMessageIdHeader() { return messageIdHeader; }
    public void setMessageIdHeader(String messageIdHeader) { this.messageIdHeader = messageIdHeader; }

    public String getInReplyTo() { return inReplyTo; }
    public void setInReplyTo(String inReplyTo) { this.inReplyTo = inReplyTo; }

    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }

    public String getHeaders() { return headers; }
    public void setHeaders(String headers) { this.headers = headers; }

    public int getAttachmentCount() { return attachmentCount; }
    public void setAttachmentCount(int attachmentCount) { this.attachmentCount = attachmentCount; }

    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
}
