package com.luke.engine.capability.phone;

import com.luke.engine.capability.secrets.ManagedBy;
import com.luke.engine.capability.secrets.SecretStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Resolves the Vapi private API key to use for a tenant — the per-tenant key from the
 * encrypted {@link SecretStore}, or the global fallback key when the tenant hasn't
 * connected its own. Mirrors how {@code EmailServerService} resolves a per-tenant
 * Postmark token with a configured fallback. The key is a secret: it is stored encrypted,
 * never persisted on a tenant-readable row, and never returned over HTTP.
 */
@Service
public class VapiCredentials {

    /** Secret-store key under which each tenant's Vapi private key is stored. */
    static final String VAPI_API_KEY_SECRET = "vapi.api-key";

    private final SecretStore secretStore;

    /** Global fallback Vapi private key (dev / single-account mode). */
    @Value("${luke.phone.vapi.api-key:}")
    private String fallbackApiKey;

    public VapiCredentials(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    /** The Vapi private key for this tenant, or "" if neither a tenant key nor a fallback is set. */
    public String resolveApiKey(String tenantId) {
        String tenantKey = secretStore.get(tenantId, VAPI_API_KEY_SECRET).orElse(null);
        if (tenantKey != null && !tenantKey.isBlank()) return tenantKey;
        return fallbackApiKey != null ? fallbackApiKey : "";
    }

    /** Store (or replace) this tenant's own Vapi private key, encrypted. */
    public void putApiKey(String tenantId, String apiKey) {
        secretStore.put(tenantId, VAPI_API_KEY_SECRET, apiKey, ManagedBy.SYSTEM);
    }

    /** Remove this tenant's Vapi key (falling back to the global key on the next call). */
    public boolean clearApiKey(String tenantId) {
        return secretStore.delete(tenantId, VAPI_API_KEY_SECRET);
    }

    /** Whether this tenant has its own connected key (independent of the global fallback). */
    public boolean hasTenantKey(String tenantId) {
        return secretStore.get(tenantId, VAPI_API_KEY_SECRET).filter(s -> !s.isBlank()).isPresent();
    }
}
