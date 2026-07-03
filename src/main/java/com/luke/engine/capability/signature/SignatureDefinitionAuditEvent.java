package com.luke.engine.capability.signature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Immutable, append-only audit row for a {@link SignatureDefinition}'s design-time lifecycle
 * (created / checked_out / checked_in / signed_off / published / …). Mirrors {@code FormAuditEvent}
 * — actor + action + free-form detail + timestamp. Distinct from the runtime, IP-stamped
 * {@link SignatureAuditEvent} (which records signer activity, not design-time edits).
 */
@Entity
@Table(
    name = "luke_signature_def_audit",
    indexes = {
        @Index(name = "idx_sigdefaudit_def", columnList = "definitionId,at"),
        @Index(name = "idx_sigdefaudit_tenant", columnList = "tenantId")
    }
)
public class SignatureDefinitionAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String definitionId;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String action;

    private String actor;

    @Column(length = 1024)
    private String detail;

    @Column(nullable = false, updatable = false)
    private LocalDateTime at = LocalDateTime.now();

    public SignatureDefinitionAuditEvent() {}

    public SignatureDefinitionAuditEvent(String definitionId, String tenantId, String action, String actor, String detail) {
        this.definitionId = definitionId;
        this.tenantId = tenantId;
        this.action = action;
        this.actor = actor;
        this.detail = detail;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDefinitionId() { return definitionId; }
    public void setDefinitionId(String definitionId) { this.definitionId = definitionId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public LocalDateTime getAt() { return at; }
    public void setAt(LocalDateTime at) { this.at = at; }
}
