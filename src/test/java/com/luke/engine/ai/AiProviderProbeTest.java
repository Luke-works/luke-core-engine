package com.luke.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifying a workspace's key against a local HTTP server standing in for the provider — so what
 * is asserted is the actual request that goes on the wire, and the actual decision taken from the
 * reply.
 *
 * <p>The distinction that matters most here is "the provider refused this key" versus "we could
 * not get an answer". Only the first may switch a workspace off; getting that wrong means an
 * outage at Groq disconnects every workspace on Groq.
 */
class AiProviderProbeTest {

    record Seen(String path, Map<String, String> headers, String query) {}

    private HttpServer server;
    private final List<Seen> seen = new ArrayList<>();
    private volatile int status = 200;
    private volatile String reply = "{\"data\":[]}";
    private AiProviderProbe probe;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            // RAW query: getQuery() decodes, which would hide whether we encoded at all.
            seen.add(new Seen(exchange.getRequestURI().getPath(), headers, exchange.getRequestURI().getRawQuery()));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        probe = new AiProviderProbe();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AiProviderCatalog.Provider provider(AiProviderCatalog.Auth auth) {
        return new AiProviderCatalog.Provider("test", "Test Provider", base + "/v1/models",
                auth, "default-model", "sk-", "https://example.test");
    }

    /* ── what goes on the wire ────────────────────────────────────────────── */

    @Test
    void bearerProvidersSendTheKeyAsAnAuthorizationHeader() {
        probe.verify(provider(AiProviderCatalog.Auth.BEARER), "sk-abc123");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).headers()).containsEntry("authorization", "Bearer sk-abc123");
    }

    @Test
    void anthropicSendsTheKeyAndTheRequiredApiVersion() {
        probe.verify(provider(AiProviderCatalog.Auth.X_API_KEY), "sk-ant-abc");
        Map<String, String> headers = seen.get(0).headers();
        assertThat(headers).containsEntry("x-api-key", "sk-ant-abc");
        // Anthropic 400s without it, which would read as UNREACHABLE and hide a good key.
        assertThat(headers).containsKey("anthropic-version");
        assertThat(headers).doesNotContainKey("authorization");
    }

    @Test
    void googleSendsTheKeyUrlEncodedOnTheQueryString() {
        probe.verify(provider(AiProviderCatalog.Auth.QUERY), "AIza+slash/plus=pad");
        String query = seen.get(0).query();
        // Unencoded, a reserved character would silently make this a different request.
        assertThat(query).contains("key=AIza%2Bslash%2Fplus%3Dpad");
        assertThat(seen.get(0).headers()).doesNotContainKey("authorization");
    }

    /* ── reading the answer ───────────────────────────────────────────────── */

    @Test
    void a401MeansTheProviderRefusedTheKey() {
        status = 401;
        AiProviderProbe.Result r = probe.verify(provider(AiProviderCatalog.Auth.BEARER), "sk-bad");
        assertThat(r.outcome()).isEqualTo(AiProviderProbe.Outcome.INVALID);
        assertThat(r.message()).contains("rejected this key");
    }

    @Test
    void a403MeansTheProviderRefusedTheKey() {
        status = 403;
        assertThat(probe.verify(provider(AiProviderCatalog.Auth.BEARER), "sk-bad").outcome())
                .isEqualTo(AiProviderProbe.Outcome.INVALID);
    }

    @Test
    void aRateLimitedAccountIsNotABadKey() {
        // The key authenticated — the account is busy. Marking this INVALID would disconnect a
        // working workspace in the middle of a traffic spike.
        status = 429;
        AiProviderProbe.Result r = probe.verify(provider(AiProviderCatalog.Auth.BEARER), "sk-good");
        assertThat(r.outcome()).isEqualTo(AiProviderProbe.Outcome.UNREACHABLE);
        assertThat(r.message()).contains("key looks fine");
    }

    @Test
    void aProviderOutageSaysNothingAboutTheKey() {
        for (int code : new int[] {500, 502, 503}) {
            status = code;
            assertThat(probe.verify(provider(AiProviderCatalog.Auth.BEARER), "sk-good").outcome())
                    .as("status %d", code)
                    .isEqualTo(AiProviderProbe.Outcome.UNREACHABLE);
        }
    }

    @Test
    void anUnreachableProviderIsNotABadKey() {
        server.stop(0);  // nothing listening
        AiProviderProbe.Result r = probe.verify(provider(AiProviderCatalog.Auth.BEARER), "sk-good");
        assertThat(r.outcome()).isEqualTo(AiProviderProbe.Outcome.UNREACHABLE);
    }

    /* ── the model list ───────────────────────────────────────────────────── */

    @Test
    void readsTheOpenAiShapedModelList() {
        reply = "{\"data\":[{\"id\":\"gpt-5-nano\"},{\"id\":\"gpt-4o\"}]}";
        AiProviderProbe.Result r = probe.verify(provider(AiProviderCatalog.Auth.BEARER), "sk-good");
        assertThat(r.ok()).isTrue();
        assertThat(r.models()).containsExactly("gpt-4o", "gpt-5-nano");  // sorted for a stable picker
    }

    @Test
    void readsTheGoogleShapedModelListAndStripsThePrefix() {
        reply = "{\"models\":[{\"name\":\"models/gemini-2.0-flash\"},{\"name\":\"models/gemini-1.5-pro\"}]}";
        assertThat(probe.verify(provider(AiProviderCatalog.Auth.QUERY), "AIzaGood").models())
                .containsExactly("gemini-1.5-pro", "gemini-2.0-flash");
    }

    @Test
    void anUnreadableModelListStillMeansTheKeyIsGood() {
        // A provider adding a field, or returning something new, must not block connecting.
        for (String body : new String[] {"not json at all", "{}", "{\"data\":\"nope\"}", "[]"}) {
            reply = body;
            AiProviderProbe.Result r = probe.verify(provider(AiProviderCatalog.Auth.BEARER), "sk-good");
            assertThat(r.ok()).as("body %s", body).isTrue();
            assertThat(r.models()).isEmpty();
        }
    }

    /* ── the paste-error guard ────────────────────────────────────────────── */

    @Test
    void anAnthropicKeyIsNotMistakenForAnOpenAiKey() {
        // Both start with "sk-", so the looser OpenAI check has to exclude Anthropic explicitly.
        assertThat(AiProviderCatalog.looksLikeKeyFor(AiProviderCatalog.OPENAI, "sk-ant-api03-xyz")).isFalse();
        assertThat(AiProviderCatalog.looksLikeKeyFor(AiProviderCatalog.ANTHROPIC, "sk-ant-api03-xyz")).isTrue();
        assertThat(AiProviderCatalog.looksLikeKeyFor(AiProviderCatalog.OPENAI, "sk-proj-xyz")).isTrue();
    }

    @Test
    void eachProviderRecognisesOnlyItsOwnKeys() {
        assertThat(AiProviderCatalog.looksLikeKeyFor(AiProviderCatalog.GROQ, "gsk_abc")).isTrue();
        assertThat(AiProviderCatalog.looksLikeKeyFor(AiProviderCatalog.GROQ, "sk-abc")).isFalse();
        assertThat(AiProviderCatalog.looksLikeKeyFor(AiProviderCatalog.GEMINI, "AIzaSyAbc")).isTrue();
        assertThat(AiProviderCatalog.looksLikeKeyFor(AiProviderCatalog.GEMINI, "gsk_abc")).isFalse();
        for (AiProviderCatalog.Provider p : AiProviderCatalog.all()) {
            assertThat(AiProviderCatalog.looksLikeKeyFor(p, null)).as(p.id()).isFalse();
            assertThat(AiProviderCatalog.looksLikeKeyFor(p, "   ")).as(p.id()).isFalse();
        }
    }

    /* ── regressions found by adversarial review ─────────────────────────── */

    @Test
    void aKeyWithAStrayNewlineNeverReachesTheRequestBuilder() {
        // HttpRequest.Builder.header() validates the value and throws an IllegalArgumentException
        // that QUOTES IT IN FULL — so one newline in a pasted key put the key in our logs.
        for (String bad : new String[] {"gsk_secret\nmore", "gsk_secret\r\n", "gsk secret",
                                        "gsk_secret\t", "gsk_\u201Csmartquote\u201D"}) {
            AiProviderProbe.Result r = probe.verify(provider(AiProviderCatalog.Auth.BEARER), bad);
            assertThat(r.outcome()).as("%s", bad).isEqualTo(AiProviderProbe.Outcome.INVALID);
            assertThat(r.message()).doesNotContain("secret").doesNotContain(bad);
            assertThat(seen).as("nothing should go on the wire").isEmpty();
        }
    }

    @Test
    void anEmptyKeyIsRejectedWithoutACall() {
        assertThat(probe.verify(provider(AiProviderCatalog.Auth.BEARER), "").outcome())
                .isEqualTo(AiProviderProbe.Outcome.INVALID);
        assertThat(probe.verify(provider(AiProviderCatalog.Auth.BEARER), null).outcome())
                .isEqualTo(AiProviderProbe.Outcome.INVALID);
        assertThat(seen).isEmpty();
    }

    @Test
    void googleSaysABadKeyWithA400NotA401() {
        // Reported as UNREACHABLE this becomes a 503 "try again in a moment", and the workspace
        // could never connect a mistyped Gemini key — it would just be told to keep waiting.
        status = 400;
        reply = "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\","
                + "\"message\":\"API key not valid. Please pass a valid API key.\"}}";
        AiProviderProbe.Result r = probe.verify(provider(AiProviderCatalog.Auth.QUERY), "AIzaWrong");
        assertThat(r.outcome()).isEqualTo(AiProviderProbe.Outcome.INVALID);
        assertThat(r.message()).contains("rejected this key");
    }

    @Test
    void anOrdinary400IsStillNotAJudgementOnTheKey() {
        status = 400;
        reply = "{\"error\":{\"message\":\"pageSize must be positive\"}}";
        assertThat(probe.verify(provider(AiProviderCatalog.Auth.QUERY), "AIzaGood").outcome())
                .isEqualTo(AiProviderProbe.Outcome.UNREACHABLE);
    }

    @Test
    void everyCatalogEntryIsUsable() {
        // A provider the agents service doesn't know would 400 every turn after a successful connect.
        assertThat(AiProviderCatalog.all()).allSatisfy(p -> {
            assertThat(p.modelsUrl()).startsWith("https://");
            assertThat(p.defaultModel()).isNotBlank();
            assertThat(p.keyPrefix()).isNotBlank();
            assertThat(AiProviderCatalog.find(p.id())).contains(p);
        });
        assertThat(AiProviderCatalog.find("ollama")).isEmpty();   // no account, nothing to bring
        assertThat(AiProviderCatalog.find(null)).isEmpty();
        assertThat(AiProviderCatalog.find("  GROQ ")).contains(AiProviderCatalog.GROQ);
    }
}
