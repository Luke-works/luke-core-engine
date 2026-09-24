package com.luke.engine.capability.access;

import com.luke.engine.capability.capability.CapabilitySubscriptionRepository;
import com.luke.engine.capability.email.EmailMessageRepository;
import com.luke.engine.capability.email.EmailVerificationRepository;
import com.luke.engine.capability.email.InboundEmailRepository;
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
    /** Received message bodies — share luke_email_messages' key, so they cascade with it. */
    private final InboundEmailRepository inboundEmails;
    private final FormInstanceRepository formInstances;
    private final FormAuditEventRepository formAuditEvents;
    private final EmailVerificationRepository emailVerifications;
    // Form payments: the charge records carry amounts tied to submissions, and the connected-account row
    // names where the tenant's money went — neither outlives the tenant.
    private final com.luke.engine.payments.FormPaymentRepository formPayments;
    private final com.luke.engine.payments.PaymentAccountRepository paymentAccounts;
    private final com.luke.engine.payments.PaymentConnectStateRepository paymentConnectStates;
    private final com.luke.engine.payments.PaymentAccountService paymentAccountService;
    /** The workspace's own LLM API key must not outlive the workspace. */
    private final com.luke.engine.ai.AiProviderService aiProviders;

    public CapabilityAdminController(CapabilityGrantRepository grants,
                                     CapabilitySubscriptionRepository subscriptions,
                                     EmailMessageRepository emailMessages,
                                     InboundEmailRepository inboundEmails,
                                     FormInstanceRepository formInstances,
                                     FormAuditEventRepository formAuditEvents,
                                     EmailVerificationRepository emailVerifications,
                                     com.luke.engine.payments.FormPaymentRepository formPayments,
                                     com.luke.engine.payments.PaymentAccountRepository paymentAccounts,
                                     com.luke.engine.payments.PaymentConnectStateRepository paymentConnectStates,
                                     com.luke.engine.payments.PaymentAccountService paymentAccountService,
                                     com.luke.engine.ai.AiProviderService aiProviders) {
        this.formPayments = formPayments;
        this.paymentAccounts = paymentAccounts;
        this.paymentConnectStates = paymentConnectStates;
        this.paymentAccountService = paymentAccountService;
        this.aiProviders = aiProviders;
        this.grants = grants;
        this.subscriptions = subscriptions;
        this.emailMessages = emailMessages;
        this.inboundEmails = inboundEmails;
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
        inboundEmails.deleteByTenant(tenantId); // received bodies, before their envelopes
        emailMessages.deleteByTenant(tenantId);
        // Open charges are cancelled and the Stripe grant revoked once this commits (best-effort).
        paymentAccountService.beforeTenantPurge(tenantId);
        formPayments.deleteByTenant(tenantId); // charge records, alongside the submissions they priced
        formInstances.deleteByTenant(tenantId);
        formAuditEvents.deleteByTenant(tenantId);
        paymentAccounts.deleteByTenant(tenantId);
        paymentConnectStates.deleteByTenant(tenantId);
        emailVerifications.deleteByTenant(tenantId);
        // The workspace's own LLM API key (in luke_secrets) and the row naming its provider. A
        // credential that outlived the workspace would keep billing someone for an account
        // nobody can see any more.
        aiProviders.forget(tenantId);
        return ResponseEntity.noContent().build();
    }

    /** Wipe a deleted user's grants in every tenant they belonged to. */
    @DeleteMapping("/users/{userId}")
    @Transactional // #62: the batch grant delete is atomic (no partially-purged user).
    public ResponseEntity<Void> purgeUser(@PathVariable String userId) {
        grants.deleteAll(grants.findByUserId(userId));
        // Their per-workspace AI model choices. No secret, but no reason to outlive them either.
        aiProviders.forgetUser(userId);
        return ResponseEntity.noContent().build();
    }
}
