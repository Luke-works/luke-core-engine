package com.luke.engine.recipient;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * One OTP challenge for a recipient PORTAL session — the code mailed/texted to a recipient to prove
 * control of their (sender-asserted) email before an email-scoped portal session is minted. Keyed by
 * {@code (tenantId, email)} (not one capability item): one active challenge per recipient per tenant,
 * replaced on re-request. The code is never stored in the clear (salted SHA-256), expires, and is
 * attempt-capped. Capability-agnostic — the recipient hub spans forms, signatures, and beyond.
 */
@Entity
@Table(
    name = "luke_portal_otp",
    uniqueConstraints = @UniqueConstraint(name = "uq_portal_otp_tenant_email", columnNames = {"tenantId", "recipientEmail"})
)
public class PortalOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** Normalised (lower-cased, trimmed) recipient email this challenge belongs to. */
    @Column(nullable = false, length = 320)
    private String recipientEmail;

    /** How the code was delivered (EMAIL/SMS) — informational; verification only checks the code. */
    @Column(nullable = false, length = 16)
    private String channel;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private String codeSalt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public PortalOtp() {}

    public String getId() { return id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }

    public String getCodeSalt() { return codeSalt; }
    public void setCodeSalt(String codeSalt) { this.codeSalt = codeSalt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
