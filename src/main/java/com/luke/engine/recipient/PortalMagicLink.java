package com.luke.engine.recipient;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A single-use magic-link challenge for a recipient PORTAL session. The link carries a high-entropy
 * random token; only its SHA-256 hash is stored (256 bits of entropy needs no per-row salt — unlike
 * a 6-digit OTP), so consume looks the row up directly by {@code tokenHash}. Expires, and is marked
 * {@code consumedAt} on first use. Bounded to one active link per {@code (tenantId, email)} — issuing
 * a new link clears prior links for that pair.
 */
@Entity
@Table(
    name = "luke_portal_magic_link",
    indexes = @Index(name = "idx_portal_magic_tenant_email", columnList = "tenantId,recipientEmail")
)
public class PortalMagicLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** Normalised (lower-cased, trimmed) recipient email this link authenticates. */
    @Column(nullable = false, length = 320)
    private String recipientEmail;

    /** Unsalted SHA-256 of the raw link token (the raw token is never stored). */
    @Column(nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime consumedAt;

    public PortalMagicLink() {}

    public String getId() { return id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
}
