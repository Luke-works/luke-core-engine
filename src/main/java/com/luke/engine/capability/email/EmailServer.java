package com.luke.engine.capability.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A company's dedicated Postmark Server, provisioned via the Postmark Account API
 * so each tenant gets isolated sending — its own server token, streams, stats and
 * sender reputation. One row per tenant.
 *
 * <p>{@code senderDomain} is the company's verified mail subdomain (e.g.
 * {@code acme.lukeflow.com}); sends are only allowed from a From on this domain
 * (see {@link EmailServerService}). The Postmark send token is NOT stored here — it
 * lives encrypted in the secret store, keyed by tenant.
 */
@Entity
@Table(
    name = "luke_email_servers",
    indexes = {
        @Index(name = "idx_emailserver_tenant", columnList = "tenantId", unique = true),
        @Index(name = "idx_emailserver_postmark", columnList = "postmarkServerId")
    }
)
public class EmailServer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String tenantId;

    /** Company slug used to derive the sender subdomain, e.g. "acme". */
    @Column(nullable = false)
    private String companySlug;

    /** Verified mail subdomain the company sends from, e.g. "acme.lukeflow.com". */
    @Column(nullable = false)
    private String senderDomain;

    /** Default sender when a request omits From, e.g. "no-reply@acme.lukeflow.com". */
    @Column(nullable = false)
    private String defaultFrom;

    /** Postmark Server numeric id returned by the Account API. */
    @Column(nullable = false)
    private Long postmarkServerId;

    /** Display name of the Postmark server, e.g. "Lukeflow — acme". */
    private String serverName;

    /** Default Postmark message stream for this server's transactional mail. */
    @Column(nullable = false)
    private String messageStream = "outbound";

    /** Lifecycle: ACTIVE (only state for now). */
    @Column(nullable = false)
    private String status = "ACTIVE";

    /** The official email that passed OTP verification, e.g. "jane@acme.com" (null if provisioned manually). */
    private String verifiedEmail;

    /** The corporate domain proven by OTP, e.g. "acme.com". */
    private String verifiedDomain;

    /** When OTP verification completed; null for a manually-provisioned server. */
    private LocalDateTime verifiedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    /**
     * Per-tenant unguessable token embedded in the inbound webhook URL
     * ({@code /api/public/email/inbound/{token}}). Set when inbound is first enabled;
     * resolves an inbound Postmark POST back to this tenant. Never returned to clients.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String inboundHookToken;

    public EmailServer() {}

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCompanySlug() { return companySlug; }
    public void setCompanySlug(String companySlug) { this.companySlug = companySlug; }

    public String getSenderDomain() { return senderDomain; }
    public void setSenderDomain(String senderDomain) { this.senderDomain = senderDomain; }

    public String getDefaultFrom() { return defaultFrom; }
    public void setDefaultFrom(String defaultFrom) { this.defaultFrom = defaultFrom; }

    public Long getPostmarkServerId() { return postmarkServerId; }
    public void setPostmarkServerId(Long postmarkServerId) { this.postmarkServerId = postmarkServerId; }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getMessageStream() { return messageStream; }
    public void setMessageStream(String messageStream) { this.messageStream = messageStream; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getVerifiedEmail() { return verifiedEmail; }
    public void setVerifiedEmail(String verifiedEmail) { this.verifiedEmail = verifiedEmail; }

    public String getVerifiedDomain() { return verifiedDomain; }
    public void setVerifiedDomain(String verifiedDomain) { this.verifiedDomain = verifiedDomain; }

    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getInboundHookToken() { return inboundHookToken; }
    public void setInboundHookToken(String inboundHookToken) { this.inboundHookToken = inboundHookToken; }
}
