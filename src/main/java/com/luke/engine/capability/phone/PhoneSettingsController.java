package com.luke.engine.capability.phone;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-tenant phone configuration, guarded by the PHONE capability (read to view, write to change).
 * Holds the default assistant/number used when a call omits them, and connects/disconnects the
 * tenant's own Vapi private key. The key is write-only — stored encrypted and never returned (the
 * response only ever exposes a {@code hasApiKey} flag). Tenant-scoped via {@code X-Tenant-Id}.
 */
@RestController
@RequestMapping("/api/phone-settings")
public class PhoneSettingsController {

    private final PhoneSettingsService settings;

    public PhoneSettingsController(PhoneSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public PhoneSettings current(@RequestHeader("X-Tenant-Id") String tenantId) {
        requireTenant(tenantId);
        return settings.get(tenantId);
    }

    public record DefaultsRequest(String defaultAssistantId, String defaultPhoneNumberId) {}

    @PutMapping
    public PhoneSettings updateDefaults(@RequestHeader("X-Tenant-Id") String tenantId,
                                        @RequestBody DefaultsRequest body) {
        requireTenant(tenantId);
        return settings.updateDefaults(tenantId, body.defaultAssistantId(), body.defaultPhoneNumberId());
    }

    public record ApiKeyRequest(String apiKey) {}

    /** Connect (or replace) this tenant's Vapi private key. */
    @PostMapping("/api-key")
    public PhoneSettings connectApiKey(@RequestHeader("X-Tenant-Id") String tenantId,
                                       @RequestBody ApiKeyRequest body) {
        requireTenant(tenantId);
        if (body == null || body.apiKey() == null || body.apiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "apiKey is required");
        }
        return settings.connectApiKey(tenantId, body.apiKey());
    }

    @DeleteMapping("/api-key")
    public PhoneSettings disconnectApiKey(@RequestHeader("X-Tenant-Id") String tenantId) {
        requireTenant(tenantId);
        return settings.disconnectApiKey(tenantId);
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }
}
