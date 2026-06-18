package com.luke.engine.capability.access;

import com.luke.engine.capability.capability.CapabilitySubscriptionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Destructive cleanup used by core-engine's account/tenant deletion cascade.
 *
 *   DELETE /api/tenants/{tenantId}  → purge a tenant's subscriptions AND all of its grants
 *   DELETE /api/users/{userId}      → purge a user's grants across every tenant
 *
 * <p>Server-to-server only: core-engine calls these after it has authorized and
 * performed the matching identity-side deletion. Idempotent — purging nothing is
 * a successful no-op so the cascade can be retried safely.
 */
@RestController
@RequestMapping("/api")
public class CapabilityAdminController {

    private final CapabilityGrantRepository grants;
    private final CapabilitySubscriptionRepository subscriptions;

    public CapabilityAdminController(CapabilityGrantRepository grants,
                                     CapabilitySubscriptionRepository subscriptions) {
        this.grants = grants;
        this.subscriptions = subscriptions;
    }

    /** Wipe everything tied to a deleted tenant: its subscriptions and every grant under it. */
    @DeleteMapping("/tenants/{tenantId}")
    public ResponseEntity<Void> purgeTenant(@PathVariable String tenantId) {
        grants.deleteAll(grants.findByTenantId(tenantId));
        subscriptions.deleteAll(subscriptions.findByTenantId(tenantId));
        return ResponseEntity.noContent().build();
    }

    /** Wipe a deleted user's grants in every tenant they belonged to. */
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Void> purgeUser(@PathVariable String userId) {
        grants.deleteAll(grants.findByUserId(userId));
        return ResponseEntity.noContent().build();
    }
}
