package com.luke.engine.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The real {@link StripeConnectGateway}.
 *
 * <p>PaymentIntents and Accounts go through the {@code stripe-java} {@link StripeClient} (one client,
 * per-request {@code Stripe-Account} + idempotency key). The two Connect OAuth calls are plain form
 * POSTs to {@code connect.stripe.com}: the SDK only exposes them through static, process-global
 * configuration, which would couple this module to billing's client and to test ordering.
 */
@Component
public class StripeConnectApi implements StripeConnectGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaymentsProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile StripeClient client;

    public StripeConnectApi(PaymentsProperties props) {
        this.props = props;
    }

    private StripeClient client() {
        StripeClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    StripeClient.StripeClientBuilder b = StripeClient.builder()
                            .setApiKey(props.secretKey())
                            .setMaxNetworkRetries(2)
                            .setConnectTimeout(10_000)
                            .setReadTimeout(30_000);
                    if (!props.apiBase().isEmpty()) b.setApiBase(props.apiBase());
                    c = b.build();
                    client = c;
                }
            }
        }
        return c;
    }

    /* ── OAuth ─────────────────────────────────────────────────────────────── */

    @Override
    public String authorizeUrl(String state) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", props.connectClientId());
        q.put("scope", "read_write");
        q.put("state", state);
        q.put("redirect_uri", props.connectRedirectUrl());
        // Most tenants already have a Stripe account: open on sign-in rather than sign-up.
        q.put("stripe_landing", "login");
        return props.connectBase() + "/oauth/authorize?" + form(q);
    }

    @Override
    public ConnectedAccount exchangeCode(String code) {
        JsonNode body = oauthPost("/oauth/token", Map.of("grant_type", "authorization_code", "code", code));
        String accountId = body.path("stripe_user_id").asText("");
        if (accountId.isEmpty()) {
            throw new GatewayException("Stripe returned no account id", "no_account", true, null);
        }
        return new ConnectedAccount(accountId, body.path("livemode").asBoolean(false), body.path("scope").asText(""));
    }

    @Override
    public void deauthorize(String accountId) {
        oauthPost("/oauth/deauthorize", Map.of("client_id", props.connectClientId(), "stripe_user_id", accountId));
    }

    private JsonNode oauthPost(String path, Map<String, String> params) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.connectBase() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + props.secretKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GatewayException("Stripe Connect is unreachable", "network", false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("Interrupted calling Stripe Connect", "network", false, e);
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(res.body() == null || res.body().isBlank() ? "{}" : res.body());
        } catch (IOException e) {
            throw new GatewayException("Stripe Connect returned an unreadable response", "bad_response", false, e);
        }
        if (res.statusCode() >= 400 || body.hasNonNull("error")) {
            String code = body.path("error").isTextual() ? body.path("error").asText()
                    : body.path("error").path("code").asText("http_" + res.statusCode());
            // error_description can echo the submitted code; keep it out of exception messages.
            int status = res.statusCode();
            boolean client = status < 500 && status != 429 && status != 409;
            throw new GatewayException("Stripe Connect refused the request (" + code + ")", code, client, status, false, null);
        }
        return body;
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /* ── Accounts & PaymentIntents ────────────────────────────────────────── */

    @Override
    public AccountSnapshot retrieveAccount(String accountId) {
        try {
            Account a = client().accounts().retrieve(accountId, RequestOptions.getDefault());
            String name = null;
            if (a.getSettings() != null && a.getSettings().getDashboard() != null) {
                name = a.getSettings().getDashboard().getDisplayName();
            }
            if ((name == null || name.isBlank()) && a.getBusinessProfile() != null) {
                name = a.getBusinessProfile().getName();
            }
            return new AccountSnapshot(a.getId(), Boolean.TRUE.equals(a.getChargesEnabled()),
                    Boolean.TRUE.equals(a.getDetailsSubmitted()), name, a.getDefaultCurrency(), a.getCountry());
        } catch (StripeException e) {
            throw wrap(e);
        }
    }

    @Override
    public IntentSnapshot createPaymentIntent(CreateIntent r) {
        PaymentIntentCreateParams.Builder b = PaymentIntentCreateParams.builder()
                .setAmount(r.amountMinor())
                .setCurrency(r.currency().toLowerCase(java.util.Locale.ROOT))
                // Card only — must match the Payment Element's paymentMethodTypes (see the form-react adapter).
                .addPaymentMethodType("card")
                .putAllMetadata(r.metadata());
        if (r.description() != null && !r.description().isBlank()) {
            b.setDescription(r.description().length() > 1000 ? r.description().substring(0, 1000) : r.description());
        }
        RequestOptions opts = RequestOptions.builder()
                .setStripeAccount(r.accountId())
                .setIdempotencyKey(r.idempotencyKey())
                .build();
        try {
            return snapshot(client().paymentIntents().create(b.build(), opts));
        } catch (StripeException e) {
            throw wrap(e);
        }
    }

    @Override
    public IntentSnapshot retrievePaymentIntent(String accountId, String intentId) {
        try {
            return snapshot(client().paymentIntents().retrieve(intentId, onAccount(accountId)));
        } catch (StripeException e) {
            throw wrap(e);
        }
    }

    @Override
    public IntentSnapshot cancelPaymentIntent(String accountId, String intentId) {
        try {
            return snapshot(client().paymentIntents().cancel(intentId, onAccount(accountId)));
        } catch (StripeException e) {
            throw wrap(e);
        }
    }

    @Override
    public ChargeState retrieveChargeState(String accountId, String intentId) {
        try {
            PaymentIntent pi = client().paymentIntents().retrieve(intentId,
                    PaymentIntentRetrieveParams.builder().addExpand("latest_charge").build(), onAccount(accountId));
            Charge ch = pi.getLatestChargeObject();
            long refunded = ch == null || ch.getAmountRefunded() == null ? 0 : ch.getAmountRefunded();
            return new ChargeState(refunded, ch != null && Boolean.TRUE.equals(ch.getDisputed()));
        } catch (StripeException e) {
            throw wrap(e);
        }
    }

    private static RequestOptions onAccount(String accountId) {
        return RequestOptions.builder().setStripeAccount(accountId).build();
    }

    private static IntentSnapshot snapshot(PaymentIntent pi) {
        StripeError err = pi.getLastPaymentError();
        return new IntentSnapshot(pi.getId(), pi.getStatus(), pi.getAmount() == null ? 0 : pi.getAmount(),
                pi.getCurrency(), pi.getClientSecret(), Boolean.TRUE.equals(pi.getLivemode()),
                err == null ? null : (err.getDeclineCode() != null ? err.getDeclineCode() : err.getCode()),
                err == null ? null : err.getMessage());
    }

    /**
     * Classify a Stripe failure.
     *
     * <ul>
     *   <li><b>Access lost</b> — only on Stripe's explicit signals: {@code account_invalid},
     *       {@code resource_missing}, or a 401/403 saying the key "does not have access to account". A bare
     *       403/404 is NOT enough: a restricted key missing a permission, or a key from the wrong platform,
     *       answers that way for every account, and must never disconnect tenants or end their charges.</li>
     *   <li><b>Transient</b> — no response, 5xx, 429 (rate limited), 409 (another request holds the
     *       idempotency key), and any other 401/403 (a platform key problem an operator must fix).</li>
     *   <li><b>Client error</b> — every other 4xx: Stripe refused this request itself.</li>
     * </ul>
     */
    static GatewayException wrap(StripeException e) {
        Integer status = e.getStatusCode();
        String code = e.getCode();
        String message = e.getStripeError() != null && e.getStripeError().getMessage() != null
                ? e.getStripeError().getMessage() : String.valueOf(e.getMessage());
        boolean noAccess = message.contains("does not have access to account");
        boolean lost = "account_invalid".equals(code) || "resource_missing".equals(code)
                || (status != null && (status == 401 || status == 403) && noAccess);
        boolean transientStatus = status == null || status >= 500 || status == 401 || status == 403
                || status == 409 || status == 429;
        boolean client = lost || !transientStatus;
        return new GatewayException("Stripe request failed (" + code + ")", code, client, status, lost, e);
    }
}
