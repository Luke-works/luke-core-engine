package com.luke.engine.branding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single home for the "Developed at Lukeflow" badge rule.
 *
 * <p>The badge is our attribution on every public form surface (the embed iframe and the outbound
 * respond/portal fill pages) — free tenants are marketing for us, so on the free plan it is ALWAYS
 * shown and the per-form option is locked. A PAYING tenant ({@link TenantPlan#PLAN_PAID}) may turn it
 * off per form; the default stays on, so a paid customer keeps crediting us until they opt out.
 *
 * <p>Both public surfaces and the authoring API resolve visibility through here, so the free-tier
 * rule can never diverge between "what the builder shows" and "what a filler sees". The stored
 * per-form preference is never rewritten when a tenant downgrades: the badge simply reappears while
 * they are free, and their original choice comes back if they upgrade again.
 */
@Service
public class BrandingPolicy {

    private static final Logger log = LoggerFactory.getLogger(BrandingPolicy.class);

    private final TenantPlanRepository plans;

    public BrandingPolicy(TenantPlanRepository plans) {
        this.plans = plans;
    }

    /**
     * May this tenant hide the badge? Only paying tenants can. Fails CLOSED: a blank tenant or an
     * unreachable plan table means "no" (badge stays on) — the badge must never disappear because of
     * an infrastructure blip, and the public render must never fail over branding.
     */
    @Transactional(readOnly = true)
    public boolean canHideBadge(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return false;
        try {
            return plans.findById(tenantId).map(TenantPlan::isPaid).orElse(false);
        } catch (RuntimeException e) {
            log.warn("BrandingPolicy: plan lookup failed for tenant {} — keeping the badge visible", tenantId, e);
            return false;
        }
    }

    /**
     * Effective badge visibility for one form.
     *
     * @param formPreference the form's stored "show the badge" choice ({@code null} = never set → on)
     * @return true when the badge must be rendered on this form's public surfaces
     */
    public boolean showBadge(String tenantId, Boolean formPreference) {
        if (!canHideBadge(tenantId)) return true;      // free plan → forced on (this is the marketing)
        return formPreference == null || formPreference; // paid plan → their call, defaulting to on
    }

    /** The plan string for a tenant ({@link TenantPlan#PLAN_FREE} when there is no row). */
    @Transactional(readOnly = true)
    public String planOf(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return TenantPlan.PLAN_FREE;
        return plans.findById(tenantId).map(TenantPlan::getPlan).orElse(TenantPlan.PLAN_FREE);
    }
}
