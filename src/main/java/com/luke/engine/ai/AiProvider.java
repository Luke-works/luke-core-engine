package com.luke.engine.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A workspace's connected AI provider — one row per tenant ({@code id} IS the tenantId).
 *
 * <p><strong>The key is not here.</strong> It lives in {@code luke_secrets} under
 * {@code ai.provider-key}, encrypted with AES-256-GCM like every other tenant secret. This row
 * holds only what we can safely show a human and what we need to decide whether a turn may run:
 * which provider, which model, whether the key last worked, and a fingerprint that lets us tell
 * "they rotated the key" from "they re-saved the same one" without keeping the key twice.
 *
 * <p>A disconnected workspace keeps its row (who connected it, when, when it ended) and loses
 * its secret. The row is the audit trail; the secret is the capability.
 */
@Entity
@Table(name = "luke_ai_provider")
public class AiProvider {

    /** Verified and usable. */
    public static final String CONNECTED = "CONNECTED";
    /** The provider rejected the key. AI is off until the workspace fixes it. */
    public static final String INVALID = "INVALID";
    /** The workspace removed it; the secret is gone. */
    public static final String DISCONNECTED = "DISCONNECTED";

    /** The name this tenant's provider key is stored under in {@code luke_secrets}. */
    public static final String SECRET_NAME = "ai.provider-key";

    @Id
    private String id;

    @Column(nullable = false)
    private String provider;

    /** The workspace's chosen model, or null to use the provider's default. */
    private String model;

    @Column(nullable = false)
    private String status = CONNECTED;

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

    /** Why the provider last refused, for the connect page to show. Never contains the key. */
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

    public AiProvider(String tenantId) {
        this.id = tenantId;
    }

    /** Whether a turn may run on this workspace's credential right now. */
    public boolean usable() {
        return CONNECTED.equals(status);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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
