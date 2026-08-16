package com.luke.engine.branding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a tenant's commercial {@link PlanCatalog} tier and the entitlements it unlocks. The one
 * place the rest of the platform asks "what plan is this tenant on, and what does it include?".
 *
 * <p>Reads the {@link TenantPlan} row through {@link TenantPlanRepository}; <b>absent row = {@link
 * PlanCatalog#FREE}</b> (the fail-closed default), and any lookup failure also degrades to FREE so a
 * billing-table blip can never hand out a paid entitlement. Same discipline as {@link BrandingPolicy}
 * / {@link PlanFeatures}, generalized: those two now read their one flag through this catalog, and new
 * plan gates go here rather than sprouting more one-off services.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final TenantPlanRepository plans;

    public PlanService(TenantPlanRepository plans) {
        this.plans = plans;
    }

    /** The tenant's tier — {@link PlanCatalog#FREE} when there is no row or the lookup fails. */
    @Transactional(readOnly = true)
    public PlanCatalog tierOf(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return PlanCatalog.FREE;
        try {
            return plans.findById(tenantId).map(row -> PlanCatalog.fromStored(row.getPlan())).orElse(PlanCatalog.FREE);
        } catch (RuntimeException e) {
            log.warn("PlanService: plan lookup failed for tenant {} — treating as {}", tenantId, PlanCatalog.FREE.id(), e);
            return PlanCatalog.FREE;
        }
    }

    /** Does the tenant's plan include (permit subscribing to) the given capability code? */
    @Transactional(readOnly = true)
    public boolean includesCapability(String tenantId, String capabilityCode) {
        return tierOf(tenantId).includes(capabilityCode);
    }
}
