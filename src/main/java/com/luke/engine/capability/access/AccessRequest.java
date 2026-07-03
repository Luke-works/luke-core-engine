package com.luke.engine.capability.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;

/**
 * A member's request for capability access they don't yet have, plus its
 * approval lifecycle. A member with no grant (or a lower level than they need)
 * asks for {@code read} | {@code read-write} on a capability the tenant is
 * subscribed to; an org owner approves (which actually grants via
 * {@link CapabilityGrantController#setGrant}) or denies.
 *
 * <p>Status flows: {@code PENDING} → {@code APPROVED} | {@code DENIED} (owner)
 * or {@code CANCELLED} (requester). Tenant-scoped like {@link CapabilityGrant}.
 */
@Entity
@Table(
    name = "luke_capability_access_requests",
    indexes = {
        @Index(name = "idx_access_request_tenant_status", columnList = "tenantId,status"),
        @Index(name = "idx_access_request_tenant_user", columnList = "tenantId,userId")
    }
)
public class AccessRequest {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String DENIED = "DENIED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** Requester's engine userId, e.g. {@code workos:user_2abc}. */
    @Column(nullable = false)
    private String userId;

    /** References {@link com.luke.engine.capability.capability.Capability#getCode()}, e.g. "FORMS". */
    @Column(nullable = false)
    private String capabilityCode;

    /** {@link CapabilityLevel}: read | read-write. */
    @Column(nullable = false)
    private String requestedLevel;

    /** {@link #PENDING} | {@link #APPROVED} | {@link #DENIED} | {@link #CANCELLED}. */
    @Column(nullable = false)
    private String status = PENDING;

    /** Requester's justification (optional). */
    private String note;

    /** Owner's decision note (optional, typically on deny). */
    private String decisionNote;

    /** Owner who approved/denied (engine userId), or null while pending. */
    private String decidedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime requestedAt = LocalDateTime.now();

    private LocalDateTime decidedAt;

    private LocalDateTime updatedAt;

    /* ── read-time display enrichment (never persisted) ─────────── */
    @Transient
    private String requesterName;
    @Transient
    private String decidedByName;
    @Transient
    private String capabilityName;

    public AccessRequest() {}

    public AccessRequest(String tenantId, String userId, String capabilityCode, String requestedLevel) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.capabilityCode = capabilityCode;
        this.requestedLevel = requestedLevel;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getCapabilityCode() { return capabilityCode; }
    public void setCapabilityCode(String capabilityCode) { this.capabilityCode = capabilityCode; }

    public String getRequestedLevel() { return requestedLevel; }
    public void setRequestedLevel(String requestedLevel) { this.requestedLevel = requestedLevel; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }

    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }

    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getRequesterName() { return requesterName; }
    public void setRequesterName(String requesterName) { this.requesterName = requesterName; }

    public String getDecidedByName() { return decidedByName; }
    public void setDecidedByName(String decidedByName) { this.decidedByName = decidedByName; }

    public String getCapabilityName() { return capabilityName; }
    public void setCapabilityName(String capabilityName) { this.capabilityName = capabilityName; }
}
