package com.luke.engine.capability.emailtemplate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * An immutable audit record of a lifecycle action on an {@link EmailTemplate}
 * (created, checked-in, published, archived, deleted, restored, …). Tenant-scoped
 * and addressed by {@code emailTemplateId}; never updated. Drives the activity feed.
 * Mirrors {@link com.luke.engine.capability.form.FormAuditEvent}.
 */
@Entity
@Table(
    name = "luke_email_template_audit",
    indexes = {
        @Index(name = "idx_emailtplaudit_tpl", columnList = "emailTemplateId"),
        @Index(name = "idx_emailtplaudit_tenant", columnList = "tenantId")
    }
)
public class EmailTemplateAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String emailTemplateId;

    @Column(nullable = false)
    private String tenantId;

    /** Lifecycle verb, e.g. "created", "checked_in", "published", "archived". */
    @Column(nullable = false)
    private String action;

    /** Optional context, e.g. "v3" or "cloned from ET-ABCD-09JUN26". */
    private String detail;

    /** The acting userId (the verified token sub), or null when unknown. */
    private String actor;

    @Column(nullable = false, updatable = false)
    private LocalDateTime at = LocalDateTime.now();

    public EmailTemplateAuditEvent() {}

    public EmailTemplateAuditEvent(String emailTemplateId, String tenantId, String action, String detail, String actor) {
        this.emailTemplateId = emailTemplateId;
        this.tenantId = tenantId;
        this.action = action;
        this.detail = detail;
        this.actor = actor;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEmailTemplateId() { return emailTemplateId; }
    public void setEmailTemplateId(String emailTemplateId) { this.emailTemplateId = emailTemplateId; }

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
