package com.luke.engine.billing;

import com.luke.engine.branding.PlanCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stripe billing configuration — all env-sourced, all optional. The whole billing module is
 * <b>config-gated</b>: with no {@code STRIPE_SECRET_KEY} set, {@link #enabled()} is false and every
 * billing entrypoint self-disables (checkout 404s, the webhook no-ops), so dev/qa and any environment
 * that hasn't wired Stripe behave exactly as before — no network, no keys, no boot dependency. This is
 * the fleet's default-lenient law applied to money.
 *
 * <p>Price ids map a {@link PlanCatalog} tier to a Stripe Price (created in the Stripe dashboard).
 * They are placeholders until real ids are supplied via {@code STRIPE_PRICE_PRO} /
 * {@code STRIPE_PRICE_BUSINESS}; a tier with no configured price simply isn't purchasable
 * self-serve (Enterprise never is — it's a sales motion; Free is a downgrade, not a checkout).
 */
@Component
public class BillingProperties {

    private final String secretKey;
    private final String webhookSecret;
    private final String pricePro;
    private final String priceBusiness;
    private final String successUrl;
    private final String cancelUrl;

    public BillingProperties(
            @Value("${luke.billing.stripe.secret-key:}") String secretKey,
            @Value("${luke.billing.stripe.webhook-secret:}") String webhookSecret,
            @Value("${luke.billing.stripe.price.pro:}") String pricePro,
            @Value("${luke.billing.stripe.price.business:}") String priceBusiness,
            @Value("${luke.billing.success-url:http://localhost:5173/plans?checkout=success}") String successUrl,
            @Value("${luke.billing.cancel-url:http://localhost:5173/plans?checkout=cancelled}") String cancelUrl) {
        this.secretKey = trim(secretKey);
        this.webhookSecret = trim(webhookSecret);
        this.pricePro = trim(pricePro);
        this.priceBusiness = trim(priceBusiness);
        this.successUrl = trim(successUrl);
        this.cancelUrl = trim(cancelUrl);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** True only when a secret key is configured — the master switch for the module. */
    public boolean enabled() {
        return !secretKey.isEmpty();
    }

    /** True when the webhook can verify signatures (a secret is set). */
    public boolean webhookConfigured() {
        return !webhookSecret.isEmpty();
    }

    public String secretKey() {
        return secretKey;
    }

    public String webhookSecret() {
        return webhookSecret;
    }

    public String successUrl() {
        return successUrl;
    }

    public String cancelUrl() {
        return cancelUrl;
    }

    /** The Stripe Price id configured for a tier, or {@code null} if that tier is not purchasable. */
    public String priceFor(PlanCatalog tier) {
        if (tier == PlanCatalog.PRO) return pricePro.isEmpty() ? null : pricePro;
        if (tier == PlanCatalog.BUSINESS) return priceBusiness.isEmpty() ? null : priceBusiness;
        return null; // FREE = downgrade, ENTERPRISE = sales — never a self-serve checkout
    }

    /** Reverse map: which tier a Stripe Price id belongs to, or {@code null} if unrecognized. */
    public PlanCatalog tierForPrice(String priceId) {
        if (priceId == null || priceId.isBlank()) return null;
        String p = priceId.trim();
        if (!pricePro.isEmpty() && pricePro.equals(p)) return PlanCatalog.PRO;
        if (!priceBusiness.isEmpty() && priceBusiness.equals(p)) return PlanCatalog.BUSINESS;
        return null;
    }

    /** The tier ids a tenant can actually buy right now (those with a configured price). */
    public List<String> purchasableTiers() {
        List<String> out = new ArrayList<>();
        for (PlanCatalog tier : List.of(PlanCatalog.PRO, PlanCatalog.BUSINESS)) {
            if (priceFor(tier) != null) out.add(tier.id());
        }
        return out;
    }

    /** Resolve a tier id (case-insensitive) to a purchasable tier, or {@code null} if not buyable. */
    public PlanCatalog purchasableTier(String tierId) {
        if (tierId == null || tierId.isBlank()) return null;
        String want = tierId.trim().toUpperCase(Locale.ROOT);
        for (PlanCatalog tier : List.of(PlanCatalog.PRO, PlanCatalog.BUSINESS)) {
            if (tier.id().equals(want) && priceFor(tier) != null) return tier;
        }
        return null;
    }
}
