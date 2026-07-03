package com.luke.engine.capability.phone;

import org.springframework.stereotype.Service;

/**
 * Reads and updates a tenant's {@link PhoneSettings} — the defaults applied when a call doesn't
 * name a number/assistant, plus connecting/disconnecting the tenant's own Vapi private key. The
 * key itself goes to the encrypted secret store via {@link VapiCredentials}; only the
 * "has a key" flag and the defaults live on the settings row (so the key is never returned).
 */
@Service
public class PhoneSettingsService {

    private final PhoneSettingsRepository repository;
    private final VapiCredentials credentials;

    public PhoneSettingsService(PhoneSettingsRepository repository, VapiCredentials credentials) {
        this.repository = repository;
        this.credentials = credentials;
    }

    /** The tenant's settings, or a fresh (unsaved) default row if none exists yet. */
    public PhoneSettings get(String tenantId) {
        return repository.findById(tenantId).orElseGet(() -> new PhoneSettings(tenantId));
    }

    /** Update the default assistant / phone number used when a call omits them. */
    public PhoneSettings updateDefaults(String tenantId, String defaultAssistantId, String defaultPhoneNumberId) {
        PhoneSettings cfg = repository.findById(tenantId).orElseGet(() -> new PhoneSettings(tenantId));
        cfg.setDefaultAssistantId(blankToNull(defaultAssistantId));
        cfg.setDefaultPhoneNumberId(blankToNull(defaultPhoneNumberId));
        return repository.save(cfg);
    }

    /** Connect (or replace) this tenant's own Vapi private key. */
    public PhoneSettings connectApiKey(String tenantId, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        credentials.putApiKey(tenantId, apiKey.trim());
        PhoneSettings cfg = repository.findById(tenantId).orElseGet(() -> new PhoneSettings(tenantId));
        cfg.setHasApiKey(true);
        return repository.save(cfg);
    }

    /** Disconnect the tenant's key (calls then fall back to the global key, if any). */
    public PhoneSettings disconnectApiKey(String tenantId) {
        credentials.clearApiKey(tenantId);
        PhoneSettings cfg = repository.findById(tenantId).orElseGet(() -> new PhoneSettings(tenantId));
        cfg.setHasApiKey(false);
        return repository.save(cfg);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
