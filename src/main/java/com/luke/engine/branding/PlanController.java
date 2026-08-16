package com.luke.engine.branding;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-serve, read-only plan for the CURRENT tenant:
 *
 * <pre>
 *   GET /api/plan  → { plan, displayName, rank, priceUsd, limits{…}, features{…}, capabilities[…] }
 * </pre>
 *
 * <p>Reads the caller's own tenant from {@code X-Tenant-Id} (mirroring {@code GET /api/my-subscriptions}) —
 * no operator gate, because seeing your own plan + entitlements is not privileged. Only the operator
 * {@link TenantPlanController} can CHANGE a plan. A missing / blank tenant resolves to {@link
 * PlanCatalog#FREE} (fail-closed), so the UI always gets a coherent plan to render.
 */
@RestController
@RequestMapping("/api")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping("/plan")
    public Map<String, Object> myPlan(@RequestHeader(name = "X-Tenant-Id", required = false) String tenantId) {
        return planService.tierOf(tenantId).toView();
    }
}
