package com.luke.engine.capability.access;

import com.luke.engine.capability.capability.CapabilitySubscription;
import com.luke.engine.capability.capability.CapabilitySubscriptionRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Resolves a user's effective access to a capability by combining the two
 * layers: the tenant must be ACTIVE-subscribed to the capability, AND the user
 * must hold a grant. Missing either → no access (deny by default).
 */
@Service
public class CapabilityAccessService {

    private static final String SUBSCRIPTION_ACTIVE = "ACTIVE";

    private final CapabilityGrantRepository grants;
    private final CapabilitySubscriptionRepository subscriptions;

    public CapabilityAccessService(CapabilityGrantRepository grants,
                                   CapabilitySubscriptionRepository subscriptions) {
        this.grants = grants;
        this.subscriptions = subscriptions;
    }

    /** Whether the tenant currently has the capability switched on. */
    public boolean tenantHasCapability(String tenantId, String capabilityCode) {
        return subscriptions.findByTenantIdAndCapabilityCode(tenantId, capabilityCode)
                .map(s -> SUBSCRIPTION_ACTIVE.equals(s.getStatus()))
                .orElse(false);
    }

    /**
     * The user's effective level for one capability, or {@code null} if they have
     * no access (tenant not subscribed, or no grant).
     */
    public String effectiveLevel(String tenantId, String userId, String capabilityCode) {
        if (!tenantHasCapability(tenantId, capabilityCode)) return null;
        return grants.findByTenantIdAndUserIdAndCapabilityCode(tenantId, userId, capabilityCode)
                .map(CapabilityGrant::getLevel)
                .orElse(null);
    }

    /** True if the user may perform the action; {@code needWrite} → requires ordinary write. */
    public boolean isAllowed(String tenantId, String userId, String capabilityCode, boolean needWrite) {
        return CapabilityLevel.satisfies(effectiveLevel(tenantId, userId, capabilityCode), needWrite);
    }

    /** True if the user's effective level permits the named {@link CapabilityLevel.Action} (#104). */
    public boolean permits(String tenantId, String userId, String capabilityCode, CapabilityLevel.Action action) {
        return CapabilityLevel.permits(effectiveLevel(tenantId, userId, capabilityCode), action);
    }

    /**
     * Every capability this user effectively has in the tenant → level. Only
     * includes capabilities the tenant is subscribed to AND the user is granted.
     * This is what the auth layer reads to tell the UI "what can I do".
     */
    public Map<String, String> effectiveCapabilities(String tenantId, String userId) {
        // One query for the tenant's ACTIVE subscriptions, then a set lookup per grant —
        // instead of a tenantHasCapability() DB hit per grant (the N+1, #51).
        Set<String> activeCaps = subscriptions.findByTenantIdAndStatus(tenantId, SUBSCRIPTION_ACTIVE)
                .stream().map(CapabilitySubscription::getCapabilityCode).collect(Collectors.toSet());
        Map<String, String> out = new LinkedHashMap<>();
        for (CapabilityGrant g : grants.findByTenantIdAndUserId(tenantId, userId)) {
            if (activeCaps.contains(g.getCapabilityCode())) {
                out.put(g.getCapabilityCode(), g.getLevel());
            }
        }
        return out;
    }
}
