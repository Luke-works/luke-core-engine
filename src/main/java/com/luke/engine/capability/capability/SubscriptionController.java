package com.luke.engine.capability.capability;

import com.luke.engine.branding.PlanService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-tenant capability enablement.
 *
 *   GET    /api/my-subscriptions                       → capabilities active for the caller's tenant (X-Tenant-Id)
 *   GET    /api/tenants/{tenantId}/capabilities        → a tenant's subscriptions (admin view)
 *   PUT    /api/tenants/{tenantId}/capabilities/{code} → enable (create/activate) a capability for a tenant
 *   DELETE /api/tenants/{tenantId}/capabilities/{code} → disable (SUSPEND, or ?hard=true to remove)
 */
@RestController
@RequestMapping("/api")
public class SubscriptionController {

    private final CapabilitySubscriptionRepository subscriptions;
    private final CapabilityRepository capabilities;
    private final PlanService planService;
    /** Off by default (dev/qa unaffected): flip on in prod to gate capabilities by the tenant's plan tier. */
    private final boolean enforceTiers;

    public SubscriptionController(CapabilitySubscriptionRepository subscriptions, CapabilityRepository capabilities,
                                  PlanService planService,
                                  @Value("${luke.plan.enforce-capability-tiers:false}") boolean enforceTiers) {
        this.subscriptions = subscriptions;
        this.capabilities = capabilities;
        this.planService = planService;
        this.enforceTiers = enforceTiers;
    }

    /** What the current tenant can use — active subscriptions joined to non-retired catalog entries. */
    @GetMapping("/my-subscriptions")
    public List<SubscribedCapability> mySubscriptions(
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        return resolve(tenantId, "ACTIVE");
    }

    @GetMapping("/tenants/{tenantId}/capabilities")
    public List<SubscribedCapability> listForTenant(@PathVariable String tenantId) {
        return resolve(tenantId, null);
    }

    @PutMapping("/tenants/{tenantId}/capabilities/{code}")
    public SubscribedCapability enable(@PathVariable String tenantId, @PathVariable String code) {
        Capability capability = capabilities.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown capability: " + code));
        if ("RETIRED".equals(capability.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Capability is retired: " + code);
        }
        // Plan-tier gate — default-lenient: off unless luke.plan.enforce-capability-tiers=true, so dev/qa is
        // unaffected. When on, a tenant may only enable a capability its plan tier includes.
        if (enforceTiers && !planService.includesCapability(tenantId, code)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Your plan does not include " + code + " — upgrade to enable it.");
        }
        CapabilitySubscription subscription = subscriptions
                .findByTenantIdAndCapabilityCode(tenantId, code)
                .orElseGet(() -> new CapabilitySubscription(tenantId, code));
        subscription.setStatus("ACTIVE");
        subscriptions.save(subscription);
        return SubscribedCapability.of(capability, subscription.getStatus());
    }

    @DeleteMapping("/tenants/{tenantId}/capabilities/{code}")
    public ResponseEntity<Void> disable(@PathVariable String tenantId, @PathVariable String code,
                                        @RequestParam(defaultValue = "false") boolean hard) {
        CapabilitySubscription subscription = subscriptions
                .findByTenantIdAndCapabilityCode(tenantId, code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Tenant " + tenantId + " has no subscription for " + code));
        if (hard) {
            subscriptions.delete(subscription);
        } else {
            subscription.setStatus("SUSPENDED");
            subscriptions.save(subscription);
        }
        return ResponseEntity.noContent().build();
    }

    /** Join a tenant's subscriptions to the catalog. {@code statusFilter} null = all; else only that subscription status. */
    private List<SubscribedCapability> resolve(String tenantId, String statusFilter) {
        List<CapabilitySubscription> subs = statusFilter == null
                ? subscriptions.findByTenantId(tenantId)
                : subscriptions.findByTenantIdAndStatus(tenantId, statusFilter);
        return subs.stream()
                .map(sub -> capabilities.findByCode(sub.getCapabilityCode())
                        .filter(cap -> !"RETIRED".equals(cap.getStatus()))
                        .map(cap -> SubscribedCapability.of(cap, sub.getStatus()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
