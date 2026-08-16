package com.luke.engine.billing;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Self-serve billing for the CURRENT tenant (tenant-authenticated, {@code X-Tenant-Id}), mirroring
 * {@code GET /api/plan}:
 *
 * <pre>
 *   GET  /api/billing/config    → { enabled, purchasableTiers: [...] }
 *   POST /api/billing/checkout  ← { tier: "PRO" }  → { url }  (redirect the browser to Stripe)
 * </pre>
 *
 * <p>The UI calls {@code /config} to decide whether to show Upgrade buttons at all (and for which
 * tiers), so a Stripe-less environment simply renders no checkout affordance. Checkout returns the
 * hosted Stripe URL; the plan itself is only written later by the signature-verified webhook, never
 * from this browser-facing call.
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final StripeBillingService billing;

    public BillingController(StripeBillingService billing) {
        this.billing = billing;
    }

    public record CheckoutBody(String tier) {}

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", billing.enabled());
        out.put("purchasableTiers", billing.purchasableTiers());
        return out;
    }

    @PostMapping("/checkout")
    public Map<String, Object> checkout(@RequestHeader(name = "X-Tenant-Id", required = false) String tenantId,
                                        @RequestBody(required = false) CheckoutBody body) {
        if (body == null || body.tier() == null || body.tier().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tier is required");
        }
        String url = billing.createCheckoutSession(tenantId, body.tier());
        return Map.of("url", url);
    }
}
