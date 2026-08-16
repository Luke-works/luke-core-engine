package com.luke.engine.branding;

import com.luke.engine.audit.AdminAuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operator-only tenant PLAN admin:
 *
 * <pre>
 *   GET /api/tenants/{tenantId}/plan  → { tenantId, plan, canHideBadge, note, updatedAt }
 *   PUT /api/tenants/{tenantId}/plan  ← { "plan": "PAID" | "FREE", "note": "…" }
 * </pre>
 *
 * <p>It lives under {@code /api/tenants/**}, which {@link
 * com.luke.engine.capability.access.OperatorAuthFilter} already gates with the operator credential on
 * every method — a tenant can never promote itself to PAID, and the badge gate can't be flipped from
 * the browser. Setting {@code FREE} deletes the row (absent = free), so the free tier stays the
 * fail-closed default. Both directions are written to the durable admin audit trail (#37).
 *
 * <p>This is the manual seam until real billing exists: when it lands, the billing webhook calls the
 * same service and every consumer of {@link BrandingPolicy} is unchanged.
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/plan")
public class TenantPlanController {

    private final TenantPlanRepository plans;
    private final AdminAuditService audit;

    public TenantPlanController(TenantPlanRepository plans, AdminAuditService audit) {
        this.plans = plans;
        this.audit = audit;
    }

    public record PlanBody(String plan, String note) {}

    @GetMapping
    public Map<String, Object> get(@PathVariable String tenantId) {
        TenantPlan row = plans.findById(tenantId).orElse(null);
        return view(tenantId, row);
    }

    @PutMapping
    public Map<String, Object> set(@PathVariable String tenantId,
                                   @RequestHeader(value = "X-User-Id", required = false) String actorId,
                                   @RequestBody PlanBody body) {
        String plan = normalize(body == null ? null : body.plan());

        if (TenantPlan.PLAN_FREE.equals(plan)) {
            // Absent row IS free — drop it rather than storing "FREE", so there is exactly one
            // representation of the free tier and no way to end up with a stale paid row.
            plans.findById(tenantId).ifPresent(plans::delete);
            audit.record("tenant.plan.set", "tenant", tenantId, tenantId, actorId, true,
                    Map.of("plan", TenantPlan.PLAN_FREE));
            return view(tenantId, null);
        }

        TenantPlan row = plans.findById(tenantId).orElseGet(() -> new TenantPlan(tenantId, plan));
        row.setPlan(plan);
        if (body.note() != null) row.setNote(body.note().isBlank() ? null : body.note().trim());
        TenantPlan saved = plans.save(row);
        audit.record("tenant.plan.set", "tenant", tenantId, tenantId, actorId, true,
                Map.of("plan", plan));
        return view(tenantId, saved);
    }

    /** Reject anything but a known tier — a typo must not read as a paid plan. */
    private static String normalize(String plan) {
        String p = plan == null ? "" : plan.trim().toUpperCase(java.util.Locale.ROOT);
        // Legacy alias: the original two-tier model stored PAID — accept it as the smallest paying tier.
        if (PlanCatalog.LEGACY_PAID.equals(p)) return PlanCatalog.PRO.id();
        for (String id : PlanCatalog.ids()) {
            if (id.equals(p)) return p;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "plan must be one of " + PlanCatalog.ids() + " (legacy '" + PlanCatalog.LEGACY_PAID + "' accepted)");
    }

    private Map<String, Object> view(String tenantId, TenantPlan row) {
        PlanCatalog tier = PlanCatalog.fromStored(row != null ? row.getPlan() : TenantPlan.PLAN_FREE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("plan", tier.id());
        out.put("displayName", tier.displayName());
        // What the plan actually unlocks — spelled out so an operator can see the effect.
        out.put("canHideBadge", tier.removableBranding());
        out.put("entitlements", tier.toView());
        out.put("note", row != null ? row.getNote() : null);
        out.put("updatedAt", row != null ? row.getUpdatedAt() : null);
        return out;
    }
}
