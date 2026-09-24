package com.luke.engine.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * One person's own model choice, within one workspace.
 *
 * <p>The KEY is the workspace's ({@link AiProvider}); the MODEL is the individual's. Two people
 * in the same workspace run on the same account and the same bill, but need not run on the same
 * model — one drafting forms may want the cheap fast one, another writing a tricky workflow may
 * want the capable one. Stored server-side rather than in the browser so the choice follows a
 * person between devices, which is how a setting is expected to behave.
 *
 * <p>Scoped per (tenant, user) rather than per user: the same person in two workspaces may be on
 * two different providers, and a model name from one is meaningless to the other.
 *
 * <p>Holds no secret. The worst a leaked row reveals is which model somebody likes.
 */
@Entity
@Table(name = "luke_ai_user_pref",
        uniqueConstraints = @UniqueConstraint(name = "uq_ai_user_pref", columnNames = {"tenant_id", "user_id"}),
        indexes = @Index(name = "idx_ai_user_pref_tenant", columnList = "tenant_id"))
public class AiUserPreference {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    /** The chosen model, or null to follow the workspace's setting. */
    private String model;

    /**
     * Which provider the choice was made for.
     *
     * <p>A model name only means something to the provider that offers it. When a workspace
     * switches providers every member's stored model becomes nonsense, and sending it anyway
     * fails that person's turns while the owner's own work fine. Stamping the provider lets a
     * stale choice be ignored rather than acted on.
     */
    private String provider;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    protected AiUserPreference() {}

    public AiUserPreference(String id, String tenantId, String userId) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
