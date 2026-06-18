package com.luke.engine.capability.form;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * An immutable audit record of a lifecycle action on a {@link FormDefinition}
 * (created, checked-in, published, archived, deleted, restored, …). Tenant-scoped
 * and addressed by {@code formId}; never updated. Drives the form's activity feed.
 */
@Entity
@Table(
    name = "luke_form_audit",
    indexes = {
        @Index(name = "idx_formaudit_form", columnList = "formId"),
        @Index(name = "idx_formaudit_tenant", columnList = "tenantId")
    }
)
public class FormAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String formId;

    @Column(nullable = false)
    private String tenantId;

    /** Lifecycle verb, e.g. "created", "checked_in", "published", "archived". */
    @Column(nullable = false)
    private String action;

    /** Optional context, e.g. "v3" or "cloned from FM-ABCD-09JUN26". */
    private String detail;

    /** The acting userId (the verified token sub), or null when unknown. */
    private String actor;

    @Column(nullable = false, updatable = false)
    private LocalDateTime at = LocalDateTime.now();

    public FormAuditEvent() {}

    public FormAuditEvent(String formId, String tenantId, String action, String detail, String actor) {
        this.formId = formId;
        this.tenantId = tenantId;
        this.action = action;
        this.detail = detail;
        this.actor = actor;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFormId() { return formId; }
    public void setFormId(String formId) { this.formId = formId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public LocalDateTime getAt() { return at; }
}
