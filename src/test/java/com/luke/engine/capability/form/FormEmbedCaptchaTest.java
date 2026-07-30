package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.web.FixedWindowRateLimiter;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The captcha GATE on the public embed submit surface — the acceptance criteria, as tests.
 *
 * <p>The verifier itself is covered by {@link TurnstileVerifierTest}; this is about the wiring: that
 * the gate sits in the right place in the request, refuses the right things in the right profiles, and
 * cannot be reached by traffic that should have been dropped earlier.
 */
class FormEmbedCaptchaTest {

    private final EmbedFormResolver resolver = mock(EmbedFormResolver.class);
    private final FormVersionRepository versions = mock(FormVersionRepository.class);
    private final FormInstanceRepository instances = mock(FormInstanceRepository.class);
    private final FormSubmissionService submissions = mock(FormSubmissionService.class);
    private final FixedWindowRateLimiter limiter = mock(FixedWindowRateLimiter.class);
    private final com.luke.engine.branding.BrandingPolicy branding = new com.luke.engine.branding.BrandingPolicy(
            mock(com.luke.engine.branding.TenantPlanRepository.class));

    private final HttpServletRequest req = mock(HttpServletRequest.class);
    private final HttpServletResponse res = mock(HttpServletResponse.class);

    /** A local stand-in for Cloudflare, so nothing here touches the network. */
    private HttpServer cloudflare;
    private String verifyUrl;
    private final AtomicReference<String> verdict = new AtomicReference<>("{\"success\":true}");
    private final AtomicInteger calls = new AtomicInteger();

    private static final String SCHEMA = """
            {"entities":{"e1":{"type":"text","attributes":{"key":"fullName"}}}}""";

    @BeforeEach
    void setUp() throws IOException {
        cloudflare = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cloudflare.createContext("/siteverify", ex -> {
            calls.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] out = verdict.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        cloudflare.start();
        verifyUrl = "http://127.0.0.1:" + cloudflare.getAddress().getPort() + "/siteverify";

        FormDefinition form = new FormDefinition();
        form.setId("f1");
        form.setCode("FM-1");
        form.setName("Contact");
        form.setPublishedVersion(1);
        when(resolver.resolve(anyString())).thenReturn(
                new EmbedFormResolver.Resolved(new EmbedTokens.EmbedRef("t1", "FM-1", 1), form));
        FormVersion v = new FormVersion();
        v.setSchema(SCHEMA);
        when(versions.findByFormIdAndVersion("f1", 1)).thenReturn(Optional.of(v));

        // The real service persists the instance, which is what assigns its id; the mock has to stand in
        // for that or the controller's success payload has a null to put in a Map.of.
        doAnswer(inv -> {
            ((FormInstance) inv.getArgument(0)).setId("i1");
            return null;
        }).when(submissions).submit(any(), any(), any(), any());
    }

    @AfterEach
    void tearDown() {
        cloudflare.stop(0);
    }

    private FormEmbedController controller(TurnstileVerifier turnstile) {
        return new FormEmbedController(resolver, versions, instances, submissions, limiter, branding,
                turnstile, 60, 120, 20, 40);
    }

    private static FormEmbedController.SubmitBody body(String captchaToken) {
        return new FormEmbedController.SubmitBody(Map.of("fullName", "Ada"), null, true, captchaToken);
    }

    /* ── a missing token is refused everywhere ──────────────── */

    @Test
    void aSubmissionWithNoCaptchaTokenIsRefusedInEveryProfile() {
        for (boolean failClosed : new boolean[] { false, true }) {
            calls.set(0);
            FormEmbedController c = controller(TurnstileVerifiers.enabled(verifyUrl, failClosed));
            assertThatThrownBy(() -> c.submit("tok", body(null), req, res))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("verify that you're human");
            // A client that did not even attempt the challenge costs us no outbound request.
            assertThat(calls.get()).as("no Cloudflare call for a missing token").isZero();
        }
        verify(submissions, never()).submit(any(), any(), any(), any());
    }

    @Test
    void aBlankCaptchaTokenIsTreatedAsMissing() {
        FormEmbedController c = controller(TurnstileVerifiers.enabled(verifyUrl, false));
        assertThatThrownBy(() -> c.submit("tok", body("   "), req, res))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(calls.get()).isZero();
    }

    /* ── Cloudflare's verdict is honoured ───────────────────── */

    @Test
    void anInvalidTokenIsRefusedWithA400AndNoCloudflareDetail() {
        verdict.set("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");
        FormEmbedController c = controller(TurnstileVerifiers.enabled(verifyUrl, false));

        assertThatThrownBy(() -> c.submit("tok", body("bad-token"), req, res))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException r = (ResponseStatusException) e;
                    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    // Cloudflare's codes tell an abuser which attempt is closest to working.
                    assertThat(r.getReason()).doesNotContain("invalid-input-response");
                });
        verify(submissions, never()).submit(any(), any(), any(), any());
    }

    @Test
    void aValidTokenSubmitsExactlyAsBefore() {
        verdict.set("{\"success\":true}");
        Map<String, Object> out = controller(TurnstileVerifiers.enabled(verifyUrl, false))
                .submit("tok", body("good-token"), req, res);

        assertThat(out).containsEntry("ok", true).containsEntry("processStatus", "QUEUED");
        verify(submissions).submit(any(), any(), any(), any());
        assertThat(calls.get()).isEqualTo(1);
    }

    /* ── unreachable: the only outcome that bends ───────────── */

    @Test
    void anUnreachableCloudflareIsAllowedOutsideProd() {
        Map<String, Object> out = controller(TurnstileVerifiers.unreachable(false))
                .submit("tok", body("tok-we-cannot-check"), req, res);

        assertThat(out).containsEntry("ok", true);
        verify(submissions).submit(any(), any(), any(), any());
    }

    @Test
    void anUnreachableCloudflareIsRefusedUnderProd() {
        FormEmbedController c = controller(TurnstileVerifiers.unreachable(true));
        assertThatThrownBy(() -> c.submit("tok", body("tok-we-cannot-check"), req, res))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("verify that you're human");
        verify(submissions, never()).submit(any(), any(), any(), any());
    }

    /* ── ordering + the off switch ──────────────────────────── */

    @Test
    void aHoneypotTrippedSubmissionNeverReachesCloudflare() {
        // Ordering that matters commercially as well as technically: obvious bots are the bulk of the
        // traffic on a public endpoint, and each one must not cost an outbound verification.
        FormEmbedController c = controller(TurnstileVerifiers.enabled(verifyUrl, true));
        Map<String, Object> out = c.submit("tok",
                new FormEmbedController.SubmitBody(Map.of(Honeypot.FIELD, "i am a bot"), null, true, null),
                req, res);

        assertThat(out).containsEntry("processStatus", "DROPPED");
        assertThat(calls.get()).as("honeypot drops before any Cloudflare round-trip").isZero();
        verify(submissions, never()).submit(any(), any(), any(), any());
    }

    @Test
    void disablingTheCaptchaBypassesTheGateCleanly() {
        Map<String, Object> out = controller(TurnstileVerifiers.disabled())
                .submit("tok", body(null), req, res);

        assertThat(out).containsEntry("ok", true).containsEntry("processStatus", "QUEUED");
        assertThat(calls.get()).isZero();
        verify(submissions).submit(any(), any(), any(), any());
    }

    /* ── the render payload ─────────────────────────────────── */

    @Test
    void theRenderPayloadCarriesThePublicSitekeyAndNeverTheSecret() {
        Map<String, Object> out = controller(TurnstileVerifiers.enabled(verifyUrl, false))
                .render("tok", req, res);

        assertThat(out).containsEntry("captchaEnabled", true);
        assertThat(out).containsEntry("captchaSitekey", TurnstileVerifier.DUMMY_SITEKEY_PASS);
        // The secret must not appear anywhere in what we serve to an anonymous browser.
        assertThat(out.toString()).doesNotContain(TurnstileVerifier.DUMMY_SECRET_PASS);
    }

    @Test
    void theRenderPayloadOffersNoSitekeyWhenTheCaptchaIsOff() {
        Map<String, Object> out = controller(TurnstileVerifiers.disabled()).render("tok", req, res);
        assertThat(out).containsEntry("captchaEnabled", false);
        assertThat(out.get("captchaSitekey")).isNull();
    }
}
