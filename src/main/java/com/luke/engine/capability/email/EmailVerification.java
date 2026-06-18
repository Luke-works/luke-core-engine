package com.luke.engine.capability.email;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One OTP challenge: a code mailed to an org's official address to prove the
 * sender controls that mailbox. The code is never stored in the clear — only a
 * salted SHA-256 hash, checked in constant time. Short-lived and attempt-limited,
 * so a leaked hash is useless once it expires.
 *
 * <p>Rows are an audit trail (PENDING → VERIFIED / EXPIRED / FAILED); the latest
 * PENDING row for a tenant is the active challenge.
 */
@Entity
@Table(
    name = "luke_email_verifications",
    indexes = {
        @Index(name = "idx_emailverif_tenant", columnList = "tenantId"),
        @Index(name = "idx_emailverif_status", columnList = "status")
    }
)
public class EmailVerification {

    /** PENDING: awaiting a code. VERIFIED: confirmed. EXPIRED: timed out / superseded. FAILED: out of attempts. */
    public static final String PENDING = "PENDING";
    public static final String VERIFIED = "VERIFIED";
    public static final String EXPIRED = "EXPIRED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** Org name as asserted at request time (for the name↔domain match + audit). */
    @Column(nullable = false)
    private String orgName;

    /** The official email the code was sent to, e.g. "jane@acme.com". */
    @Column(nullable = false)
    private String email;

    /** Domain of {@link #email}, e.g. "acme.com". */
    @Column(nullable = false)
    private String domain;

    @Column(nullable = false)
    private String status = PENDING;

    /** Salted SHA-256 of the OTP; never exposed. */
    @JsonIgnore
    @Column(nullable = false)
    private String codeHash;

    /** Random per-row salt (hex); never exposed. */
    @JsonIgnore
    @Column(nullable = false)
    private String codeSalt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime verifiedAt;

    public EmailVerification() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @JsonIgnore
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }

    @JsonIgnore
    public String getCodeSalt() { return codeSalt; }
    public void setCodeSalt(String codeSalt) { this.codeSalt = codeSalt; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
}
