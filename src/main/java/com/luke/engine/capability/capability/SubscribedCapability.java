package com.luke.engine.capability.capability;

/**
 * A capability as seen by a tenant: the catalog fields plus the tenant's
 * subscription status. Matches the shape luke-core-ui's sidebar consumes from
 * /api/my-subscriptions.
 */
public record SubscribedCapability(
        String code,
        String name,
        String description,
        String icon,
        String route,
        String tier,
        String status
) {
    static SubscribedCapability of(Capability capability, String subscriptionStatus) {
        return new SubscribedCapability(
                capability.getCode(),
                capability.getName(),
                capability.getDescription(),
                capability.getIcon(),
                capability.getRoute(),
                capability.getTier(),
                subscriptionStatus
        );
    }
}
