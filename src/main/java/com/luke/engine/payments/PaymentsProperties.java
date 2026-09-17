package com.luke.engine.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Form-payments (Stripe Connect) configuration — all env-sourced, all optional.
 *
 * <p><b>Config-gated, like billing.</b> Payments need the platform's secret key, its publishable key
 * and its Connect OAuth {@code client_id}. With any of them unset {@link #enabled()} is false and the
 * whole feature self-disables: connecting a Stripe account 404s, payment forms can't be published or
 * submitted, and the Connect webhook no-ops. No environment is ever forced to hold Stripe keys.
 *
 * <p>The secret key defaults to billing's {@code STRIPE_SECRET_KEY}: Connect is the SAME platform
 * account that sells Lukeflow subscriptions, so one key serves both. The Connect webhook has its own
 * signing secret — Stripe issues a separate one for a "connected accounts" endpoint.
 *
 * <p>Test and live keys must not be mixed; a publishable key whose mode differs from the secret key's
 * disables payments with a warning rather than failing at a payer's first charge.
 */
@Component
public class PaymentsProperties {

    private static final Logger log = LoggerFactory.getLogger(PaymentsProperties.class);

    private final String secretKey;
    private final String publishableKey;
    private final String connectClientId;
    private final String connectWebhookSecret;
    private final String connectRedirectUrl;
    private final String stripeJsUrl;
    private final String apiBase;
    private final String connectBase;
    private final long pendingTtlMinutes;
    private final boolean modesAgree;
    private final boolean scriptUrlOk;

    public PaymentsProperties(
            @Value("${luke.payments.stripe.secret-key:${luke.billing.stripe.secret-key:}}") String secretKey,
            @Value("${luke.payments.stripe.publishable-key:}") String publishableKey,
            @Value("${luke.payments.stripe.connect-client-id:}") String connectClientId,
            @Value("${luke.payments.stripe.connect-webhook-secret:}") String connectWebhookSecret,
            @Value("${luke.payments.connect-redirect-url:http://localhost:5173/forms/payments}") String connectRedirectUrl,
            @Value("${luke.payments.stripe.js-url:https://js.stripe.com/v3/}") String stripeJsUrl,
            @Value("${luke.payments.stripe.api-base:}") String apiBase,
            @Value("${luke.payments.stripe.connect-base:}") String connectBase,
            @Value("${luke.payments.pending-ttl-minutes:120}") long pendingTtlMinutes) {
        this.secretKey = trim(secretKey);
        this.publishableKey = trim(publishableKey);
        this.connectClientId = trim(connectClientId);
        this.connectWebhookSecret = trim(connectWebhookSecret);
        this.connectRedirectUrl = trim(connectRedirectUrl);
        this.stripeJsUrl = trim(stripeJsUrl).isEmpty() ? "https://js.stripe.com/v3/" : trim(stripeJsUrl);
        this.apiBase = trim(apiBase);
        this.connectBase = trim(connectBase);
        this.pendingTtlMinutes = Math.max(5, pendingTtlMinutes);
        this.modesAgree = this.secretKey.isEmpty() || this.publishableKey.isEmpty()
                || isLive(this.secretKey) == this.publishableKey.startsWith("pk_live_");
        if (!modesAgree) {
            log.warn("Form payments DISABLED: STRIPE_PUBLISHABLE_KEY and the Stripe secret key are from "
                    + "different modes (live vs test). Use a matching pair.");
        }
        // Stripe.js must come from Stripe (PCI). A bad value disables payments rather than the whole engine:
        // this setting is optional, and a typo on a keyless dev box shouldn't take every capability down.
        this.scriptUrlOk = this.stripeJsUrl.startsWith("https://js.stripe.com/");
        if (!scriptUrlOk) {
            log.warn("Form payments DISABLED: STRIPE_JS_URL must start with https://js.stripe.com/ (got '{}')", this.stripeJsUrl);
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean isLive(String key) {
        return key.startsWith("sk_live_") || key.startsWith("rk_live_");
    }

    /** The master switch: every key present and of one mode. */
    public boolean enabled() {
        return !secretKey.isEmpty() && publishableKey.startsWith("pk_") && !connectClientId.isEmpty() && modesAgree
                && scriptUrlOk;
    }

    /** Whether the Connect webhook can verify signatures. */
    public boolean webhookConfigured() {
        return !connectWebhookSecret.isEmpty();
    }

    /** Live keys → only live connected accounts and live events are accepted. */
    public boolean livemode() {
        return isLive(secretKey);
    }

    public String secretKey() { return secretKey; }
    public String publishableKey() { return publishableKey; }
    public String connectClientId() { return connectClientId; }
    public String connectWebhookSecret() { return connectWebhookSecret; }
    public String connectRedirectUrl() { return connectRedirectUrl; }
    public String stripeJsUrl() { return stripeJsUrl; }
    /** Override for Stripe's API host — tests only. Blank = Stripe's default. */
    public String apiBase() { return apiBase; }
    /** Override for connect.stripe.com — tests only. Blank = Stripe's default. */
    public String connectBase() { return connectBase.isEmpty() ? "https://connect.stripe.com" : connectBase; }
    /** How long an unpaid submission waits before its charge is cancelled and the form released. */
    public long pendingTtlMinutes() { return pendingTtlMinutes; }
}
