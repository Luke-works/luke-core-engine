package com.luke.engine.capability.access;

import com.luke.engine.capability.capability.CapabilitySubscriptionRepository;
import com.luke.engine.capability.email.EmailMessageRepository;
import com.luke.engine.capability.email.EmailVerificationRepository;
import com.luke.engine.capability.form.FormAuditEventRepository;
import com.luke.engine.capability.form.FormInstanceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Destructive cleanup used by core-engine's account/tenant deletion cascade.
 *
 *   DELETE /api/tenants/{tenantId}  → purge a tenant's subscriptions, grants, and PII/audit trails
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
    // #53: a deleted tenant must leave no personal data — cascade the PII/audit trails too.
    private final EmailMessageRepository emailMessages;
    private final FormInstanceRepository formInstances;
    private final FormAuditEventRepository formAuditEvents;
    private final EmailVerificationRepository emailVerifications;

    public CapabilityAdminController(CapabilityGrantRepository grants,
                                     CapabilitySubscriptionRepository subscriptions,
                                     EmailMessageRepository emailMessages,
                                     FormInstanceRepository formInstances,
                                     FormAuditEventRepository formAuditEvents,
                                     EmailVerificationRepository emailVerifications) {
        this.grants = grants;
        this.subscriptions = subscriptions;
        this.emailMessages = emailMessages;
        this.formInstances = formInstances;
        this.formAuditEvents = formAuditEvents;
        this.emailVerifications = emailVerifications;
    }

    /** Wipe everything tied to a deleted tenant: subscriptions, grants, and its PII/audit trails
     *  (email sends, form submissions + lifecycle events, OTP challenges) — so no personal data
     *  outlives the tenant (#53 right-to-erasure). */
    @DeleteMapping("/tenants/{tenantId}")
    @Transactional // #62: the whole cascade is one atomic unit (all-or-nothing).
    public ResponseEntity<Void> purgeTenant(@PathVariable String tenantId) {
        grants.deleteAll(grants.findByTenantId(tenantId));
        subscriptions.deleteAll(subscriptions.findByTenantId(tenantId));
        emailMessages.deleteByTenant(tenantId);
        formInstances.deleteByTenant(tenantId);
        formAuditEvents.deleteByTenant(tenantId);
        emailVerifications.deleteByTenant(tenantId);
        return ResponseEntity.noContent().build();
    }

    /** Wipe a deleted user's grants in every tenant they belonged to. */
    @DeleteMapping("/users/{userId}")
    @Transactional // #62: the batch grant delete is atomic (no partially-purged user).
    public ResponseEntity<Void> purgeUser(@PathVariable String userId) {
        grants.deleteAll(grants.findByUserId(userId));
        return ResponseEntity.noContent().build();
    }
}
