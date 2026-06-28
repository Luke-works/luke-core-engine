package com.luke.engine.capability.signature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One immutable row of the IP-stamped audit trail for a {@link SignatureRequest} — the
 * legal record of who did what, from where, and when. One row per lifecycle event
 * (CREATED, SENT, VIEWED, SIGNED, DOWNLOADED, VOIDED, PURGED). Never updated.
 *
 * <p>The full ordered trail is rendered into the signed PDF's Certificate of Completion
 * (SIG-2) and surfaced in the UI history view (SIG-4). IP is personal data (GDPR),
 * retained as a legal record for the request's {@code retainUntil}.
 */
@Entity
@Table(
    name = "luke_signature_audit",
    indexes = {
        @Index(name = "idx_sigaudit_request", columnList = "requestId,at"),
        @Index(name = "idx_sigaudit_tenant", columnList = "tenantId")
    }
)
public class SignatureAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String requestId;

    @Column(nullable = false)
    private String tenantId;

    /** CREATED | SENT | VIEWED | SIGNED | DOWNLOADED | VOIDED | PURGED (see SignatureSupport.Action). */
    @Column(nullable = false)
    private String action;

    /** The acting party: the requester userId for authed events, the signerEmail for the sign event. */
    private String actor;

    /** Real client IP (X-Forwarded-For left-most public hop / X-Real-IP / socket). PII. */
    private String ipAddress;

    @Column(length = 1024)
    private String userAgent;

    /** Optional geolocation — OFF by default (NoOpIpGeoProvider); pluggable. */
    private String geoCountry;
    private String geoCity;

    /** VPN/proxy/Tor classification of {@link #ipAddress}; null when not evaluated.
     *  Pinned to VARCHAR so the column type is identical on H2 and Postgres (no native enum). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 32)
    private IpRisk ipRisk;

    /** Free-form context, e.g. "consent=true" or "blocked: TOR". */
    @Column(length = 1024)
    private String detail;

    @Column(nullable = false, updatable = false)
    private LocalDateTime at = LocalDateTime.now();

    public SignatureAuditEvent() {}

    public SignatureAuditEvent(String requestId, String tenantId, String action, String actor) {
        this.requestId = requestId;
        this.tenantId = tenantId;
        this.action = action;
        this.actor = actor;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getGeoCountry() { return geoCountry; }
    public void setGeoCountry(String geoCountry) { this.geoCountry = geoCountry; }

    public String getGeoCity() { return geoCity; }
    public void setGeoCity(String geoCity) { this.geoCity = geoCity; }

    public IpRisk getIpRisk() { return ipRisk; }
    public void setIpRisk(IpRisk ipRisk) { this.ipRisk = ipRisk; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public LocalDateTime getAt() { return at; }
}
