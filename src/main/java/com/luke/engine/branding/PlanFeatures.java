package com.luke.engine.branding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which product features a tenant's plan unlocks.
 *
 * <p>Sits beside {@link BrandingPolicy}, which owns the one rule that came first (the attribution
 * badge). This class is for everything else the plan gates, so a second paid feature does not end up
 * inside a class named after branding. The package name predates having more than one.
 *
 * <p><b>File attachments</b> are a paid feature. Attachments are the expensive part of a form: they
 * are object storage we hold, retain and serve on someone else's behalf, and a free tenant collecting
 * uploads is an unbounded cost with no revenue against it.
 *
 * <p>Every method fails CLOSED — a blank tenant or an unreachable plan table means "free". A billing
 * lookup that blips must never accidentally hand out a paid feature; the opposite mistake (briefly
 * refusing an upload a paying tenant is entitled to) is recoverable and visible.
 */
@Service
public class PlanFeatures {

    private static final Logger log = LoggerFactory.getLogger(PlanFeatures.class);

    private final TenantPlanRepository plans;

    public PlanFeatures(TenantPlanRepository plans) {
        this.plans = plans;
    }

    /** May this tenant collect file attachments on their forms? Paid plans only. */
    @Transactional(readOnly = true)
    public boolean canUseAttachments(String tenantId) {
        return isPaid(tenantId);
    }

    private boolean isPaid(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return false;
        try {
            return plans.findById(tenantId).map(TenantPlan::isPaid).orElse(false);
        } catch (RuntimeException e) {
            log.warn("PlanFeatures: plan lookup failed for tenant {} — treating as the free plan", tenantId, e);
            return false;
        }
    }
}
