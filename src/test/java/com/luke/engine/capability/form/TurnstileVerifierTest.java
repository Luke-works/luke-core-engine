package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TurnstileVerifier} against a REAL local HTTP server rather than a mocked client.
 *
 * <p>The whole point of this class is how it behaves when the network misbehaves — timeouts, non-2xx,
 * bodies that aren't JSON. Mocking {@code HttpClient} would assert that our stubs return what we told
 * them to; a socket that actually hangs, or actually returns a 500, exercises the code that matters.
 * {@code com.sun.net.httpserver} ships with the JDK, so this needs no dependency and no network.
 */
class TurnstileVerifierTest {

    private HttpServer server;
    private String url;
    /** What the next request should get: [status, body] — or null to hang until the client gives up. */
    private final AtomicReference<int[]> status = new AtomicReference<>(new int[] { 200 });
    private final AtomicReference<String> body = new AtomicReference<>("{\"success\":true}");
    private final AtomicReference<Boolean> hang = new AtomicReference<>(false);
    /** The form fields the verifier actually posted, so we can assert the wire contract. */
    private final AtomicReference<Map<String, String>> received = new AtomicReference<>(Map.of());

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/siteverify", this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/siteverify";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        received.set(parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        if (Boolean.TRUE.equals(hang.get())) {
            try {
                Thread.sleep(2000); // longer than the verifier's timeout in these tests
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status.get()[0], out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> out = new HashMap<>();
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** A verifier pointed at the local stub. Fail-closed is irrelevant here — this class only reports. */
    private TurnstileVerifier verifier(long timeoutMs) {
        return new TurnstileVerifier(true, TurnstileVerifier.DUMMY_SECRET_PASS,
                TurnstileVerifier.DUMMY_SITEKEY_PASS, timeoutMs, url, false);
    }

    /* ── the three outcomes ─────────────────────────────────── */

    @Test
    void aTokenCloudflareVouchesForIsVerified() {
        body.set("{\"success\":true,\"challenge_ts\":\"2026-07-30T00:00:00Z\"}");
        assertThat(verifier(3000).verify("tok", "203.0.113.4")).isEqualTo(TurnstileVerifier.Outcome.VERIFIED);
    }

    @Test
    void anExplicitFailureIsRejected() {
        body.set("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");
        assertThat(verifier(3000).verify("tok", null)).isEqualTo(TurnstileVerifier.Outcome.REJECTED);
    }

    @Test
    void aTimeoutIsUnreachableRatherThanARejection() {
        // The distinction that matters: "we could not ask" must never be recorded as "Cloudflare said
        // no", or a network blip would look like a wave of failed challenges and (under prod) refuse
        // every real user with no way to tell why.
        hang.set(true);
        assertThat(verifier(200).verify("tok", null)).isEqualTo(TurnstileVerifier.Outcome.UNREACHABLE);
    }

    @Test
    void aNon2xxIsUnreachable() {
        status.set(new int[] { 502 });
        body.set("upstream is unwell");
        assertThat(verifier(3000).verify("tok", null)).isEqualTo(TurnstileVerifier.Outcome.UNREACHABLE);
    }

    @Test
    void aBodyThatIsNotJsonIsUnreachableNotRejected() {
        // A 200 we cannot parse is an ABSENT verdict, not a negative one — so it follows the
        // fail-open/closed rule instead of silently refusing real users during a Cloudflare bug.
        body.set("<html>we are having trouble</html>");
        assertThat(verifier(3000).verify("tok", null)).isEqualTo(TurnstileVerifier.Outcome.UNREACHABLE);
    }

    @Test
    void jsonWithoutASuccessFieldIsRejected() {
        // Well-formed but non-committal: we CAN read it and it does not say yes.
        body.set("{\"error-codes\":[\"missing-input-response\"]}");
        assertThat(verifier(3000).verify("tok", null)).isEqualTo(TurnstileVerifier.Outcome.REJECTED);
    }

    /* ── the no-token short circuit ─────────────────────────── */

    @Test
    void aBlankOrMissingTokenIsRejectedWithoutCallingCloudflare() throws IOException {
        // Stop the server first: if any of these reached the network the call would fail and return
        // UNREACHABLE, so a REJECTED result proves the short circuit.
        server.stop(0);
        TurnstileVerifier v = verifier(3000);
        assertThat(v.verify(null, null)).isEqualTo(TurnstileVerifier.Outcome.REJECTED);
        assertThat(v.verify("", null)).isEqualTo(TurnstileVerifier.Outcome.REJECTED);
        assertThat(v.verify("   ", null)).isEqualTo(TurnstileVerifier.Outcome.REJECTED);
        start(); // so @AfterEach's stop() has something to stop
    }

    /* ── the wire contract ──────────────────────────────────── */

    @Test
    void postsTheSecretAndResponseAsFormFields() {
        verifier(3000).verify("the-token", "203.0.113.9");
        assertThat(received.get())
                .containsEntry("secret", TurnstileVerifier.DUMMY_SECRET_PASS)
                .containsEntry("response", "the-token")
                .containsEntry("remoteip", "203.0.113.9");
    }

    @Test
    void omitsRemoteIpWhenThereIsNoneRatherThanSendingBlank() {
        verifier(3000).verify("the-token", "   ");
        assertThat(received.get()).doesNotContainKey("remoteip");
    }

    /* ── configuration surface ──────────────────────────────── */

    @Test
    void exposesTheSitekeyButNeverTheSecret() {
        // The sitekey is public by design (it goes in the render payload); the secret has no getter at
        // all, which is the point — there is no accessor for a caller to accidentally serialise.
        TurnstileVerifier v = verifier(3000);
        assertThat(v.sitekey()).isEqualTo(TurnstileVerifier.DUMMY_SITEKEY_PASS);
        assertThat(TurnstileVerifier.class.getMethods())
                .noneMatch(m -> m.getName().toLowerCase(java.util.Locale.ROOT).contains("secret"));
    }

    @Test
    void carriesTheEnabledAndFailClosedFlagsItIsGiven() {
        assertThat(verifier(3000).isEnabled()).isTrue();
        assertThat(verifier(3000).failClosed()).isFalse();
        TurnstileVerifier strict = new TurnstileVerifier(false, TurnstileVerifier.DUMMY_SECRET_PASS,
                TurnstileVerifier.DUMMY_SITEKEY_PASS, 3000, url, true);
        assertThat(strict.isEnabled()).isFalse();
        assertThat(strict.failClosed()).isTrue();
    }
}
