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
 * One provider account a workspace has connected — Groq, OpenAI, Anthropic or Gemini.
 *
 * <p><b>A workspace may connect several.</b> This began as one row per tenant, which meant
 * connecting a second provider silently overwrote the first one's key: someone who had verified
 * Groq and then added Gemini lost the Groq key entirely, with no warning and no way back. Each
 * provider now has its own row and its own secret, so adding one never destroys another, and a
 * workspace can keep a cheap fast provider beside a more capable one.
 *
 * <p><strong>The key is not here.</strong> It lives in {@code luke_secrets} under
 * {@code ai.provider-key.<provider>}, encrypted with AES-256-GCM like every other tenant secret.
 * This row holds only what we can safely show a human and what we need to decide whether a turn
 * may run: which provider, which model, whether the key last worked, and a fingerprint that lets
 * us tell "they rotated the key" from "they re-saved the same one".
 *
 * <p>A disconnected provider keeps its row (who connected it, when, when it ended) and loses its
 * secret. The row is the audit trail; the secret is the capability.
 */
@Entity
@Table(name = "luke_ai_provider",
        uniqueConstraints = @UniqueConstraint(name = "uq_ai_provider_tenant", columnNames = {"tenant_id", "provider"}),
        indexes = @Index(name = "idx_ai_provider_tenant", columnList = "tenant_id"))
public class AiProvider {

    /** Verified and usable. */
    public static final String CONNECTED = "CONNECTED";
    /** The provider rejected the key. This provider is off until the workspace fixes it. */
    public static final String INVALID = "INVALID";
    /** The workspace removed it; the secret is gone. */
    public static final String DISCONNECTED = "DISCONNECTED";

    /** Where this provider's key is stored for {@code tenantId}. */
    public static String secretName(String provider) {
        return "ai.provider-key." + provider;
    }

    /** Deterministic row id, so a tenant cannot hold two rows for the same provider. */
    public static String idFor(String tenantId, String provider) {
        return tenantId + ":" + provider;
    }

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String provider;

    /** The workspace's chosen model for THIS provider, or null for the provider's default. */
    private String model;

    @Column(nullable = false)
    private String status = CONNECTED;

    /**
     * The one a turn uses when the person running it has expressed no preference.
     *
     * <p>Exactly one connected provider per workspace carries this. Without it, "which provider
     * runs this turn" would depend on row order, which is no answer at all.
     */
    @Column(nullable = false)
    private boolean preferred;

    /** Last four characters of the key — the only fragment ever shown to a human. */
    @Column(name = "key_last4", length = 8)
    private String keyLast4;

    /** SHA-256 of the key. Lets us recognise a rotation without storing the key a second time. */
    @Column(name = "key_fingerprint", length = 64)
    private String keyFingerprint;

    private String connectedBy;

    private LocalDateTime connectedAt;

    /** When the provider last confirmed the key works. */
    private LocalDateTime verifiedAt;

    private LocalDateTime disconnectedAt;

    /**
     * When this provider last told us the account is out of credit or over its quota.
     *
     * <p>Deliberately NOT a status: the key is fine and the workspace is still connected, so
     * marking it INVALID would be a lie that also demotes it and hides the real message. It is
     * a flag the settings page and the model picker can show, and it clears the moment a turn
     * on this provider succeeds — which is the only reliable signal that the credit is back.
     */
    private LocalDateTime exhaustedAt;

    /** Why the provider last refused, for the settings page to show. Never contains the key. */
    @Column(length = 500)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    protected AiProvider() {}

    public AiProvider(String tenantId, String provider) {
        this.id = idFor(tenantId, provider);
        this.tenantId = tenantId;
        this.provider = provider;
    }

    /** Whether a turn may run on this provider right now. */
    public boolean usable() {
        return CONNECTED.equals(status);
    }

    /** This provider's own secret name. */
    public String secretName() {
        return secretName(provider);
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isPreferred() {
        return preferred;
    }

    public void setPreferred(boolean preferred) {
        this.preferred = preferred;
    }

    public String getKeyLast4() {
        return keyLast4;
    }

    public void setKeyLast4(String keyLast4) {
        this.keyLast4 = keyLast4;
    }

    public String getKeyFingerprint() {
        return keyFingerprint;
    }

    public void setKeyFingerprint(String keyFingerprint) {
        this.keyFingerprint = keyFingerprint;
    }

    public String getConnectedBy() {
        return connectedBy;
    }

    public void setConnectedBy(String connectedBy) {
        this.connectedBy = connectedBy;
    }

    public LocalDateTime getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(LocalDateTime connectedAt) {
        this.connectedAt = connectedAt;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public LocalDateTime getDisconnectedAt() {
        return disconnectedAt;
    }

    public void setDisconnectedAt(LocalDateTime disconnectedAt) {
        this.disconnectedAt = disconnectedAt;
    }

    public LocalDateTime getExhaustedAt() {
        return exhaustedAt;
    }

    public void setExhaustedAt(LocalDateTime exhaustedAt) {
        this.exhaustedAt = exhaustedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
