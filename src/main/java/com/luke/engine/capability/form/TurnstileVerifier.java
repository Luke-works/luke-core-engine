package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.config.StrictProfile;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Cloudflare Turnstile verification for the PUBLIC embed submit surface — the one endpoint in the
 * system that accepts writes from an anonymous browser on someone else's website.
 *
 * <p>Deliberately scoped like {@link Honeypot}: one job, no web types, no persistence. It answers
 * exactly one question — did Cloudflare vouch for this challenge token — and leaves the decision about
 * what to DO with the answer to the caller.
 *
 * <p><b>Three outcomes, not two.</b> "Cloudflare said no" and "we could not ask Cloudflare" are
 * different facts and must not collapse into one, because they get different treatment:
 * <ul>
 *   <li>{@link Outcome#VERIFIED} — a real human challenge was solved.</li>
 *   <li>{@link Outcome#REJECTED} — Cloudflare said no, or the client sent no token at all. ALWAYS
 *       refused, in every profile. A missing token is a client that did not even try.</li>
 *   <li>{@link Outcome#UNREACHABLE} — timeout, IO error, non-2xx, or a body we cannot read. We have no
 *       verdict. This is the ONLY outcome subject to {@link #failClosed()}.</li>
 * </ul>
 *
 * <p><b>Fail-open in dev/qa, fail-closed under {@code prod}</b>, mirroring the existing strict-mode
 * guards ({@link StrictProfile}). A Cloudflare outage must not take down local development or qa; in
 * production, an unverifiable submission on an anonymous endpoint is not worth accepting.
 *
 * <p><b>The secret never leaves this class.</b> It is sent to Cloudflare and otherwise only ever
 * length-checked — it must never reach a response body, the render payload, or a log line.
 */
@Component
public class TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cloudflare's published always-passes test pair. Safe defaults: this builds and tests with no
     *  Cloudflare account and (for the sitekey) no network. */
    public static final String DUMMY_SITEKEY_PASS = "1x00000000000000000000AA";
    public static final String DUMMY_SECRET_PASS = "1x0000000000000000000000000000000AA";
    /** Cloudflare's published always-blocks pair, for exercising the refusal path. */
    public static final String DUMMY_SITEKEY_BLOCK = "2x00000000000000000000AB";
    public static final String DUMMY_SECRET_BLOCK = "2x0000000000000000000000000000000AA";

    /** Every published dummy secret. A prod deployment still using one is misconfigured — a dummy
     *  secret REJECTS real tokens, so the embed surface would refuse every genuine submission. */
    private static final List<String> DUMMY_SECRETS = List.of(DUMMY_SECRET_PASS, DUMMY_SECRET_BLOCK);

    public enum Outcome { VERIFIED, REJECTED, UNREACHABLE }

    private final boolean enabled;
    private final String secret;
    private final String sitekey;
    private final URI verifyUrl;
    private final Duration timeout;
    private final boolean failClosed;
    private final HttpClient http;

    // Explicit, because there are two constructors: without this Spring finds no default one and
    // refuses to instantiate the bean (the same reason InsecureKeyGuard annotates its own).
    @Autowired
    public TurnstileVerifier(
            @Value("${luke.embed.captcha.enabled:true}") boolean enabled,
            @Value("${luke.embed.captcha.secret:" + DUMMY_SECRET_PASS + "}") String secret,
            @Value("${luke.embed.captcha.sitekey:" + DUMMY_SITEKEY_PASS + "}") String sitekey,
            @Value("${luke.embed.captcha.timeout-ms:3000}") long timeoutMs,
            // Overridable so tests can point at a local stub server and exercise the real HTTP path —
            // timeouts, non-2xx and malformed bodies included — rather than mocking them away.
            @Value("${luke.embed.captcha.verify-url:https://challenges.cloudflare.com/turnstile/v0/siteverify}")
                    String verifyUrl,
            Environment environment) {
        this(enabled, secret, sitekey, timeoutMs, verifyUrl, StrictProfile.isActive(environment));
    }

    /** Test-friendly constructor: the effective fail-closed flag is already resolved. */
    TurnstileVerifier(boolean enabled, String secret, String sitekey, long timeoutMs, String verifyUrl,
                      boolean failClosed) {
        this.enabled = enabled;
        this.secret = secret == null ? "" : secret.trim();
        this.sitekey = sitekey == null ? "" : sitekey.trim();
        this.verifyUrl = URI.create(verifyUrl);
        this.timeout = Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 3000L);
        this.failClosed = failClosed;
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        warnIfMisconfigured();
    }

    /** Whether the embed surface should demand a challenge at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /** The PUBLIC sitekey, for the embed page to mount the widget with. Safe to serve to any client —
     *  unlike {@link #secret}, which never leaves this class. */
    public String sitekey() {
        return sitekey;
    }

    /** Whether an {@link Outcome#UNREACHABLE} verification should refuse the submission. */
    public boolean failClosed() {
        return failClosed;
    }

    /**
     * Ask Cloudflare whether {@code token} is a solved challenge.
     *
     * <p>{@code remoteIp} is optional context Cloudflare uses to strengthen its own scoring; it is
     * never required, and a null/blank value simply omits it.
     *
     * <p>Never throws. A blank token short-circuits to {@link Outcome#REJECTED} without a network call —
     * there is nothing to verify, and an anonymous endpoint should not spend an outbound request
     * discovering that.
     */
    public Outcome verify(String token, String remoteIp) {
        if (token == null || token.isBlank()) return Outcome.REJECTED;
        HttpRequest request = HttpRequest.newBuilder(verifyUrl)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(token, remoteIp), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Turnstile verification interrupted; treating as unreachable");
            return Outcome.UNREACHABLE;
        } catch (Exception e) {
            // Timeout, DNS failure, connection reset, TLS problem — we have no verdict.
            log.warn("Turnstile unreachable ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            return Outcome.UNREACHABLE;
        }
        if (response.statusCode() / 100 != 2) {
            log.warn("Turnstile returned HTTP {}; treating as unreachable", response.statusCode());
            return Outcome.UNREACHABLE;
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(response.body());
        } catch (Exception e) {
            // A 200 we cannot parse is not a "no" — it is an absent verdict, so it follows the same
            // fail-open/closed rule rather than silently refusing real users during a Cloudflare bug.
            log.warn("Turnstile response was not readable JSON; treating as unreachable");
            return Outcome.UNREACHABLE;
        }
        if (body != null && body.path("success").asBoolean(false)) return Outcome.VERIFIED;
        // Cloudflare's error codes are useful to US and meaningless (or helpful) to an abuser — log
        // them, never return them.
        log.info("Turnstile rejected a submission: {}", errorCodes(body));
        return Outcome.REJECTED;
    }

    /* ── internals ──────────────────────────────────────────── */

    private String form(String token, String remoteIp) {
        StringBuilder sb = new StringBuilder();
        sb.append("secret=").append(enc(secret));
        sb.append("&response=").append(enc(token));
        if (remoteIp != null && !remoteIp.isBlank()) {
            sb.append("&remoteip=").append(enc(remoteIp.trim()));
        }
        return sb.toString();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String errorCodes(JsonNode body) {
        if (body == null) return "[]";
        JsonNode codes = body.path("error-codes");
        if (!codes.isArray()) return "[]";
        List<String> out = new ArrayList<>();
        codes.forEach(c -> out.add(c.asText()));
        return out.toString();
    }

    /**
     * Shout about the two configurations that would silently break the embed surface. Deliberately a
     * warning, not a fail-fast: this class is not a startup guard, and refusing to boot over a captcha
     * setting would be a worse failure than the one it prevents.
     */
    private void warnIfMisconfigured() {
        if (!enabled) {
            log.warn("Turnstile is DISABLED (luke.embed.captcha.enabled=false) — the public embed submit "
                    + "surface is protected only by the honeypot, rate limits and origin allowlist.");
            return;
        }
        if (failClosed && DUMMY_SECRETS.contains(secret)) {
            log.error("Turnstile is running under the '{}' profile with a Cloudflare TEST secret. A test "
                    + "secret REJECTS real tokens, so every genuine embed submission will be refused. Set "
                    + "luke.embed.captcha.secret to the real key.", StrictProfile.PROFILE);
        }
        if (secret.isBlank()) {
            log.error("Turnstile is enabled but luke.embed.captcha.secret is blank — every verification "
                    + "will be rejected by Cloudflare.");
        }
    }
}
