package com.luke.engine.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One immutable row of the ADMIN audit trail (#37) — the durable, queryable record of a single
 * privileged administrative mutation: who did what, to which target, in which tenant, from where,
 * and when. Written from the admin controllers via {@link AuditService}.
 *
 * <p>APPEND-ONLY by construction: every column is {@code updatable = false} (Hibernate never emits an
 * UPDATE), and {@link AuditEventRepository} exposes only {@code save} + reads — there is no delete
 * path. This is distinct from {@code SignatureAuditEvent} (the per-signature-request legal trail);
 * this is the cross-cutting admin-action trail keyed by actor + action + target.
 *
 * <p>Under the postgres profile Hibernate ddl-auto is {@code none}, so
 * {@code V16__audit_event_table.sql} is the schema source of truth and must stay faithful to this
 * entity ({@code PostgresSchemaValidationTest} enforces it against a real Postgres in CI).
 */
@Entity
@Table(
    name = "luke_audit_event",
    indexes = {
        @Index(name = "idx_audit_tenant_created", columnList = "tenantId,createdAt"),
        @Index(name = "idx_audit_created", columnList = "createdAt"),
        @Index(name = "idx_audit_actor", columnList = "actorId")
    }
)
public class AuditEvent {

    @Id
    private String id;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** The resolved authenticated principal that performed the action. */
    @Column(updatable = false)
    private String actorId;

    /** True when the actor acted as a platform operator (camunda-admin / parent_cluster). */
    @Column(nullable = false, updatable = false)
    private boolean actorOperator;

    /** Tenant scope of the action; null for account-level actions that span tenants. */
    @Column(updatable = false)
    private String tenantId;

    /** Dotted action code, e.g. {@code user.create}, {@code capability.grant}, {@code tenant.delete}. */
    @Column(nullable = false, updatable = false, length = 100)
    private String action;

    /** The kind of thing acted on: {@code user}, {@code tenant}, {@code capability}, {@code candidate_group}, {@code account}. */
    @Column(updatable = false, length = 64)
    private String targetType;

    /** Id of the target (userId / tenantId / capability code / groupId). */
    @Column(updatable = false)
    private String targetId;

    /** Best-effort real client IP (X-Forwarded-For left-most public hop / X-Real-IP / socket). */
    @Column(updatable = false)
    private String sourceIp;

    @Column(updatable = false, length = 16)
    private String requestMethod;

    @Column(updatable = false, length = 512)
    private String requestPath;

    /** Correlation id of the request that produced this event (ties to the server log). */
    @Column(updatable = false)
    private String correlationId;

    /** Free-form JSON context (role/level/capability/deleted tenants ...). Clipped to the column width. */
    @Column(updatable = false, length = 4000)
    private String detail;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public boolean isActorOperator() { return actorOperator; }
    public void setActorOperator(boolean actorOperator) { this.actorOperator = actorOperator; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }
    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }
    public String getRequestPath() { return requestPath; }
    public void setRequestPath(String requestPath) { this.requestPath = requestPath; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
