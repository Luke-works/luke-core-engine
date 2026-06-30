package com.luke.engine.capability.phone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Per-tenant phone configuration: the defaults used when a caller doesn't specify
 * them, and a flag for whether the tenant has connected its own Vapi key. The Vapi
 * private API key itself is NOT stored here — it lives encrypted in the
 * {@link com.luke.engine.capability.secrets.SecretStore} (see {@link VapiCredentials}),
 * exactly as EMAIL stores per-tenant Postmark tokens. One row per tenant ({@code id}
 * is the tenantId).
 */
@Entity
@Table(name = "luke_phone_settings")
public class PhoneSettings {

    /** The tenantId — one settings row per tenant. */
    @Id
    private String id;

    /** Whether this tenant has connected its own Vapi private key (else the global fallback is used). */
    @Column(nullable = false)
    private boolean hasApiKey = false;

    /** Default assistant to answer inbound calls / place outbound calls when unspecified. */
    private String defaultAssistantId;

    /** Default Vapi phone-number id to place outbound calls from when unspecified. */
    private String defaultPhoneNumberId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public PhoneSettings() {}

    public PhoneSettings(String tenantId) {
        this.id = tenantId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isHasApiKey() { return hasApiKey; }
    public void setHasApiKey(boolean hasApiKey) { this.hasApiKey = hasApiKey; }

    public String getDefaultAssistantId() { return defaultAssistantId; }
    public void setDefaultAssistantId(String defaultAssistantId) { this.defaultAssistantId = defaultAssistantId; }

    public String getDefaultPhoneNumberId() { return defaultPhoneNumberId; }
    public void setDefaultPhoneNumberId(String defaultPhoneNumberId) { this.defaultPhoneNumberId = defaultPhoneNumberId; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
