package com.luke.engine.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real Stripe client against a local HTTP server standing in for api.stripe.com and
 * connect.stripe.com — so what is asserted is the actual request the SDK and our OAuth calls put on
 * the wire: the connected-account header, the idempotency key, card-only, and the platform key.
 */
class StripeConnectApiTest {

    record Seen(String method, String path, Map<String, String> headers, Map<String, String> form) {}

    private HttpServer server;
    private final List<Seen> seen = new ArrayList<>();
    private volatile int status = 200;
    private volatile String reply = "{}";
    private StripeConnectApi api;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, parse(body)));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        api = new StripeConnectApi(new PaymentsProperties("sk_test_platform", "pk_test_platform", "ca_client",
                "whsec_x", "https://app.example/forms/payments", "https://js.stripe.com/v3/", base, base, 120));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return out;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static final String INTENT = """
            {"id":"pi_123","object":"payment_intent","amount":4500,"currency":"usd","status":"requires_payment_method",
             "client_secret":"pi_123_secret_abc","livemode":false,
             "last_payment_error":{"code":"card_declined","decline_code":"insufficient_funds","message":"Your card has insufficient funds."}}""";

    @Test
    void authorizeUrlCarriesTheClientStateAndRedirect() {
        String url = api.authorizeUrl("st4te");
        assertThat(url).startsWith("http://127.0.0.1:");
        assertThat(url).contains("/oauth/authorize?response_type=code&client_id=ca_client&scope=read_write&state=st4te");
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fapp.example%2Fforms%2Fpayments");
        assertThat(url).contains("stripe_landing=login");
    }

    @Test
    void exchangeCodePostsWithThePlatformKey() {
        reply = "{\"stripe_user_id\":\"acct_9\",\"livemode\":false,\"scope\":\"read_write\",\"access_token\":\"sk_ignored\"}";
        StripeConnectGateway.ConnectedAccount a = api.exchangeCode("ac_1");
        assertThat(a).isEqualTo(new StripeConnectGateway.ConnectedAccount("acct_9", false, "read_write"));
        Seen s = seen.get(0);
        assertThat(s.method()).isEqualTo("POST");
        assertThat(s.path()).isEqualTo("/oauth/token");
        assertThat(s.headers().get("authorization")).isEqualTo("Bearer sk_test_platform");
        assertThat(s.form()).containsEntry("grant_type", "authorization_code").containsEntry("code", "ac_1");
    }

    @Test
    void oauthErrorsAreClassifiedAndKeepTheCodeOutOfTheMessage() {
        status = 400;
        reply = "{\"error\":\"invalid_grant\",\"error_description\":\"Authorization code does not exist: ac_secret\"}";
        assertThatThrownBy(() -> api.exchangeCode("ac_secret"))
                .isInstanceOfSatisfying(StripeConnectGateway.GatewayException.class, e -> {
                    assertThat(e.code()).isEqualTo("invalid_grant");
                    assertThat(e.clientError()).isTrue();
                    assertThat(e.getMessage()).doesNotContain("ac_secret");
                });
        status = 503;
        reply = "{\"error\":{\"code\":\"api_error\"}}";
        assertThatThrownBy(() -> api.exchangeCode("ac_2"))
                .isInstanceOfSatisfying(StripeConnectGateway.GatewayException.class, e -> assertThat(e.clientError()).isFalse());
    }

    @Test
    void deauthorizeNamesTheClientAndAccount() {
        reply = "{\"stripe_user_id\":\"acct_9\"}";
        api.deauthorize("acct_9");
        assertThat(seen.get(0).path()).isEqualTo("/oauth/deauthorize");
        assertThat(seen.get(0).form()).containsEntry("client_id", "ca_client").containsEntry("stripe_user_id", "acct_9");
    }

    @Test
    void createPaymentIntentIsACardOnlyDirectChargeWithAnIdempotencyKey() {
        reply = INTENT;
        StripeConnectGateway.IntentSnapshot s = api.createPaymentIntent(new StripeConnectGateway.CreateIntent(
                "acct_9", 4500, "USD", "Ticket", Map.of("lukeflow_submission", "inst-1"), "lukeflow-form-payment-p1"));
        Seen req = seen.get(0);
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/v1/payment_intents");
        assertThat(req.headers().get("stripe-account")).isEqualTo("acct_9");
        assertThat(req.headers().get("idempotency-key")).isEqualTo("lukeflow-form-payment-p1");
        assertThat(req.headers().get("authorization")).isEqualTo("Bearer sk_test_platform");
        assertThat(req.form())
                .containsEntry("amount", "4500")
                .containsEntry("currency", "usd")
                .containsEntry("payment_method_types[0]", "card")
                .containsEntry("description", "Ticket")
                .containsEntry("metadata[lukeflow_submission]", "inst-1")
                .doesNotContainKey("application_fee_amount")
                .doesNotContainKey("transfer_data[destination]");
        assertThat(s.id()).isEqualTo("pi_123");
        assertThat(s.clientSecret()).isEqualTo("pi_123_secret_abc");
        assertThat(s.amount()).isEqualTo(4500);
        assertThat(s.lastErrorCode()).isEqualTo("insufficient_funds");
        assertThat(s.lastErrorMessage()).isEqualTo("Your card has insufficient funds.");
    }

    @Test
    void retrieveAndCancelAreScopedToTheConnectedAccount() {
        reply = INTENT;
        api.retrievePaymentIntent("acct_9", "pi_123");
        api.cancelPaymentIntent("acct_9", "pi_123");
        assertThat(seen.get(0).method()).isEqualTo("GET");
        assertThat(seen.get(0).path()).isEqualTo("/v1/payment_intents/pi_123");
        assertThat(seen.get(0).headers().get("stripe-account")).isEqualTo("acct_9");
        assertThat(seen.get(1).method()).isEqualTo("POST");
        assertThat(seen.get(1).path()).isEqualTo("/v1/payment_intents/pi_123/cancel");
        assertThat(seen.get(1).headers().get("stripe-account")).isEqualTo("acct_9");
    }

    @Test
    void retrieveAccountReadsTheDisplayNameAndCapabilities() {
        reply = """
                {"id":"acct_9","object":"account","charges_enabled":true,"details_submitted":true,"country":"US",
                 "default_currency":"usd","business_profile":{"name":"Legal Name"},
                 "settings":{"dashboard":{"display_name":"Shop Name"}}}""";
        StripeConnectGateway.AccountSnapshot a = api.retrieveAccount("acct_9");
        assertThat(a).isEqualTo(new StripeConnectGateway.AccountSnapshot("acct_9", true, true, "Shop Name", "usd", "US"));
        assertThat(seen.get(0).path()).isEqualTo("/v1/accounts/acct_9");
        assertThat(seen.get(0).headers()).doesNotContainKey("stripe-account");
    }

    @Test
    void aCardErrorIsAClientError_andAServerErrorIsNot() {
        status = 402;
        reply = "{\"error\":{\"type\":\"card_error\",\"code\":\"amount_too_small\",\"message\":\"Amount must be at least 50 cents\"}}";
        assertThatThrownBy(() -> api.createPaymentIntent(new StripeConnectGateway.CreateIntent(
                "acct_9", 1, "USD", null, Map.of(), "k1")))
                .isInstanceOfSatisfying(StripeConnectGateway.GatewayException.class, e -> {
                    assertThat(e.clientError()).isTrue();
                    assertThat(e.code()).isEqualTo("amount_too_small");
                    assertThat(e.accessLost()).isFalse();
                });
    }

    private StripeConnectGateway.GatewayException readFails(int httpStatus, String error) {
        status = httpStatus;
        reply = "{\"error\":" + error + "}";
        try {
            api.retrievePaymentIntent("acct_9", "pi_123");
        } catch (StripeConnectGateway.GatewayException e) {
            return e;
        }
        throw new AssertionError("expected a failure");
    }

    @Test
    void failuresAreClassifiedAsTransientOrAccessLost() {
        // Another request holds the idempotency key: retry, don't fail the attempt.
        var inFlight = readFails(409, "{\"type\":\"idempotency_error\",\"code\":\"idempotency_key_in_use\",\"message\":\"in use\"}");
        assertThat(inFlight.clientError()).isFalse();
        assertThat(inFlight.accessLost()).isFalse();
        assertThat(inFlight.httpStatus()).isEqualTo(409);

        var revoked = readFails(403, "{\"type\":\"invalid_request_error\",\"code\":\"account_invalid\",\"message\":\"The provided key does not have access to account 'acct_9'.\"}");
        assertThat(revoked.clientError()).isTrue();
        assertThat(revoked.accessLost()).isTrue();

        var oldStyle = readFails(401, "{\"type\":\"invalid_request_error\",\"message\":\"The provided key 'sk_test_***' does not have access to account 'acct_9' (or that account does not exist). Application access may have been revoked.\"}");
        assertThat(oldStyle.accessLost()).isTrue();

        var badKey = readFails(401, "{\"type\":\"invalid_request_error\",\"message\":\"Invalid API Key provided: sk_test_***\"}");
        assertThat(badKey.accessLost()).isFalse();

        var gone = readFails(404, "{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\",\"message\":\"No such payment_intent\"}");
        assertThat(gone.accessLost()).isTrue();

        // A bare 403/404 is not proof of anything account-level: a restricted key missing a permission, or a
        // key from the wrong platform, answers that way for every account.
        var permission = readFails(403, "{\"type\":\"invalid_request_error\",\"message\":\"The provided key does not have the required permissions for this endpoint.\"}");
        assertThat(permission.accessLost()).isFalse();
        assertThat(permission.clientError()).isFalse();
        var bare404 = readFails(404, "{\"type\":\"invalid_request_error\",\"message\":\"Not found\"}");
        assertThat(bare404.accessLost()).isFalse();
        assertThat(bare404.clientError()).isTrue();
        var noAccess403 = readFails(403, "{\"type\":\"permission_error\",\"message\":\"The provided key does not have access to account 'acct_9'.\"}");
        assertThat(noAccess403.accessLost()).isTrue();

        var limited = readFails(429, "{\"type\":\"invalid_request_error\",\"code\":\"rate_limit\",\"message\":\"slow down\"}");
        assertThat(limited.clientError()).isFalse();
        assertThat(limited.accessLost()).isFalse();
    }

    @Test
    void aJsUrlOffStripesHostDisablesPaymentsWithoutFailingBoot() {
        PaymentsProperties bad = new PaymentsProperties("sk_test_platform", "pk_test_platform", "ca_client",
                "whsec_x", "https://app.example/forms/payments", "https://js.stripe.com", "", "", 120);
        assertThat(bad.enabled()).isFalse();
        PaymentsProperties good = new PaymentsProperties("sk_test_platform", "pk_test_platform", "ca_client",
                "whsec_x", "https://app.example/forms/payments", "https://js.stripe.com/v3/", "", "", 120);
        assertThat(good.enabled()).isTrue();
    }
}
