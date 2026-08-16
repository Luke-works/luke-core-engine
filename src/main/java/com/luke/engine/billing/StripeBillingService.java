package com.luke.engine.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.audit.AdminAuditService;
import com.luke.engine.branding.PlanCatalog;
import com.luke.engine.branding.PlanService;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Stripe integration — the machinery that turns a tenant's tier choice into a paid subscription
 * and writes the result back through the existing plan seam ({@link PlanService#applyPlan}). Two
 * directions:
 *
 * <ol>
 *   <li><b>Checkout</b> — {@link #createCheckoutSession} mints a hosted Stripe Checkout Session for a
 *       purchasable tier and returns its URL for the browser to redirect to. It never touches the
 *       plan itself; payment isn't real until Stripe says so.</li>
 *   <li><b>Webhook</b> — {@link #handleWebhook} verifies Stripe's signature and applies the truth:
 *       {@code checkout.session.completed} upgrades the tenant, {@code customer.subscription.updated}
 *       tracks tier changes, {@code customer.subscription.deleted} (or a lapsed status) downgrades to
 *       Free. This is the ONLY writer of a billed plan — the browser is never trusted to set it.</li>
 * </ol>
 *
 * <p><b>Config-gated.</b> With Stripe unconfigured ({@link BillingProperties#enabled()} false) every
 * method is a safe no-op or a clear 404, so the module never touches the network in dev/qa.
 */
@Service
public class StripeBillingService {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingService.class);

    /** Stripe subscription statuses under which the tenant still has access to their tier. */
    private static final Set<String> ACTIVE_STATUSES = Set.of("active", "trialing", "past_due");

    private final BillingProperties props;
    private final BillingCustomerRepository customers;
    private final PlanService plans;
    private final AdminAuditService audit;
    private final ObjectMapper mapper;

    private volatile StripeClient client;

    public StripeBillingService(BillingProperties props, BillingCustomerRepository customers,
                                PlanService plans, AdminAuditService audit, ObjectMapper mapper) {
        this.props = props;
        this.customers = customers;
        this.plans = plans;
        this.audit = audit;
        this.mapper = mapper;
    }

    public boolean enabled() {
        return props.enabled();
    }

    public java.util.List<String> purchasableTiers() {
        return props.enabled() ? props.purchasableTiers() : java.util.List.of();
    }

    private StripeClient client() {
        StripeClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = new StripeClient(props.secretKey());
                    client = c;
                }
            }
        }
        return c;
    }

    /* ── Checkout ─────────────────────────────────────────────────────────── */

    /**
     * Create a hosted Checkout Session for {@code tenantId} to buy {@code tierId}; returns the URL to
     * redirect the browser to. Guards run BEFORE any network call: 404 when billing is off, 400 when
     * the tier isn't self-serve purchasable (unknown / Free / Enterprise / no price configured).
     */
    public String createCheckoutSession(String tenantId, String tierId) {
        if (!props.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "billing is not enabled");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing tenant");
        }
        PlanCatalog tier = props.purchasableTier(tierId);
        if (tier == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tier is not purchasable self-serve; buyable tiers: " + props.purchasableTiers());
        }
        String priceId = props.priceFor(tier);

        SessionCreateParams.Builder b = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(props.successUrl())
                .setCancelUrl(props.cancelUrl())
                .setClientReferenceId(tenantId)
                .putMetadata("tenantId", tenantId)
                .putMetadata("tier", tier.id())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build())
                // Stamp the tenant on the subscription too, so later subscription.* events resolve
                // back to a tenant without depending on the customer-mapping table alone.
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("tenantId", tenantId)
                        .putMetadata("tier", tier.id())
                        .build());

        // Reuse the tenant's existing Stripe Customer if we've seen them before.
        customers.findById(tenantId)
                .map(BillingCustomer::getStripeCustomerId)
                .filter(id -> id != null && !id.isBlank())
                .ifPresent(b::setCustomer);

        try {
            Session session = client().checkout().sessions().create(b.build());
            return session.getUrl();
        } catch (StripeException e) {
            log.warn("Stripe checkout session failed for tenant {} tier {}: {}", tenantId, tier.id(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "could not start checkout");
        }
    }

    /* ── Webhook ──────────────────────────────────────────────────────────── */

    /**
     * Verify Stripe's signature and apply the event. A bad signature is a 400 (Stripe won't retry a
     * forgery); an unconfigured module quietly ignores the call. A handler error propagates so Stripe
     * retries. Returns the event type handled (or {@code null} when ignored) for logging/tests.
     */
    public String handleWebhook(String payload, String sigHeader) {
        if (!props.enabled() || !props.webhookConfigured()) {
            log.debug("Stripe webhook received but billing/webhook not configured — ignoring.");
            return null;
        }
        final Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, props.webhookSecret());
        } catch (SignatureVerificationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Stripe signature");
        }
        JsonNode object = dataObject(payload);
        processEvent(event.getType(), object);
        return event.getType();
    }

    /** The verified event's {@code data.object}, re-parsed from the raw payload (version-robust). */
    private JsonNode dataObject(String payload) {
        try {
            JsonNode root = mapper.readTree(payload);
            JsonNode obj = root.path("data").path("object");
            return obj.isMissingNode() ? null : obj;
        } catch (Exception e) {
            log.warn("Stripe webhook: could not parse payload body: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Dispatch a parsed event to the plan seam. Package-private so tests can drive the tier logic
     * without a real (signed) Stripe payload.
     */
    void processEvent(String type, JsonNode object) {
        if (type == null || object == null) return;
        switch (type) {
            case "checkout.session.completed" -> onCheckoutCompleted(object);
            case "customer.subscription.updated" -> onSubscriptionChanged(object, false);
            case "customer.subscription.deleted" -> onSubscriptionChanged(object, true);
            default -> log.debug("Stripe event {} not handled", type);
        }
    }

    private void onCheckoutCompleted(JsonNode obj) {
        String tenantId = firstNonBlank(text(obj, "metadata", "tenantId"), text(obj, "client_reference_id"));
        if (tenantId == null) {
            log.warn("Stripe checkout.session.completed with no tenant reference — ignoring.");
            return;
        }
        PlanCatalog tier = PlanCatalog.fromStored(text(obj, "metadata", "tier"));
        String customerId = text(obj, "customer");
        String subscriptionId = text(obj, "subscription");

        rememberCustomer(tenantId, customerId, subscriptionId);
        plans.applyPlan(tenantId, tier, provenance(subscriptionId));
        audit.record("billing.checkout.completed", "tenant", tenantId, tenantId, "stripe", false,
                detail(tier, customerId, subscriptionId));
        log.info("Billing: tenant {} upgraded to {} via Stripe checkout {}", tenantId, tier.id(), subscriptionId);
    }

    private void onSubscriptionChanged(JsonNode obj, boolean deleted) {
        String customerId = text(obj, "customer");
        String tenantId = firstNonBlank(text(obj, "metadata", "tenantId"), tenantForCustomer(customerId));
        if (tenantId == null) {
            log.warn("Stripe subscription event with no resolvable tenant (customer={}) — ignoring.", customerId);
            return;
        }
        String status = text(obj, "status");
        boolean lapsed = deleted || (status != null && !ACTIVE_STATUSES.contains(status));

        if (lapsed) {
            plans.applyPlan(tenantId, PlanCatalog.FREE, null);
            audit.record("billing.subscription.ended", "tenant", tenantId, tenantId, "stripe", false,
                    detail(PlanCatalog.FREE, customerId, text(obj, "id")));
            log.info("Billing: tenant {} downgraded to FREE (status={}, deleted={})", tenantId, status, deleted);
            return;
        }

        PlanCatalog tier = props.tierForPrice(subscriptionPriceId(obj));
        if (tier == null) {
            log.warn("Stripe subscription for tenant {} references an unknown price — leaving plan unchanged.", tenantId);
            return;
        }
        rememberCustomer(tenantId, customerId, text(obj, "id"));
        plans.applyPlan(tenantId, tier, provenance(text(obj, "id")));
        audit.record("billing.subscription.updated", "tenant", tenantId, tenantId, "stripe", false,
                detail(tier, customerId, text(obj, "id")));
        log.info("Billing: tenant {} plan set to {} (subscription update)", tenantId, tier.id());
    }

    /* ── mapping helpers ──────────────────────────────────────────────────── */

    private void rememberCustomer(String tenantId, String customerId, String subscriptionId) {
        if (customerId == null || customerId.isBlank()) return;
        try {
            BillingCustomer row = customers.findById(tenantId).orElseGet(() -> new BillingCustomer(tenantId, customerId));
            row.setStripeCustomerId(customerId);
            if (subscriptionId != null && !subscriptionId.isBlank()) row.setStripeSubscriptionId(subscriptionId);
            customers.save(row);
        } catch (RuntimeException e) {
            // The mapping is a convenience; never let it fail the webhook (the plan write is the truth).
            log.warn("Billing: could not persist Stripe customer mapping for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    private String tenantForCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) return null;
        try {
            return customers.findByStripeCustomerId(customerId).map(BillingCustomer::getId).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String subscriptionPriceId(JsonNode obj) {
        JsonNode items = obj.path("items").path("data");
        if (items.isArray() && !items.isEmpty()) {
            String id = items.get(0).path("price").path("id").asText(null);
            return (id == null || id.isBlank()) ? null : id;
        }
        return null;
    }

    private static Map<String, Object> detail(PlanCatalog tier, String customerId, String subscriptionId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("tier", tier.id());
        if (customerId != null) d.put("customer", customerId);
        if (subscriptionId != null) d.put("subscription", subscriptionId);
        return d;
    }

    private static String provenance(String subscriptionId) {
        return subscriptionId == null || subscriptionId.isBlank() ? "stripe" : "stripe:" + subscriptionId;
    }

    /** Read a nested text field, returning {@code null} for missing/blank. */
    private static String text(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            if (n == null) return null;
            n = n.get(p);
        }
        if (n == null || n.isNull()) return null;
        String v = n.asText(null);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }
}
