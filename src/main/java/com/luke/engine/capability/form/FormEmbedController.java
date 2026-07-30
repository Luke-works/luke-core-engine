package com.luke.engine.capability.form;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;

/**
 * The public, UNAUTHENTICATED embed surface — the only path in the system that
 * does not go through the WorkOS gateway. Reached as {@code /api/public/embed/**}
 * (the gateway forwards these without a user; the signed token IS the auth).
 *
 * <p>{@link EmbedTokens} validates the opaque token → tenant + form code. The GET
 * returns the published schema for an iframe to render; the POST is the inbound
 * webhook that records a submission (and, later, starts a process).
 */
@RestController
@RequestMapping("/api/public/embed")
public class FormEmbedController {

    private static final Logger log = LoggerFactory.getLogger(FormEmbedController.class);

    private final EmbedFormResolver resolver;
    private final FormVersionRepository versions;
    private final FormInstanceRepository instances;
    private final FormSubmissionService submissions;
    private final com.luke.engine.web.FixedWindowRateLimiter rateLimiter;
    private final com.luke.engine.branding.BrandingPolicy branding;
    private final TurnstileVerifier turnstile;

    // Configurable per-minute caps for the PUBLIC embed surface (#55), keyed per token AND per IP.
    private final int renderMaxPerToken;
    private final int renderMaxPerIp;
    private final int submitMaxPerToken;
    private final int submitMaxPerIp;

    public FormEmbedController(EmbedFormResolver resolver, FormVersionRepository versions,
                               FormInstanceRepository instances, FormSubmissionService submissions,
                               com.luke.engine.web.FixedWindowRateLimiter rateLimiter,
                               com.luke.engine.branding.BrandingPolicy branding,
                               TurnstileVerifier turnstile,
                               @org.springframework.beans.factory.annotation.Value("${luke.embed.render.max-per-token-per-min:60}") int renderMaxPerToken,
                               @org.springframework.beans.factory.annotation.Value("${luke.embed.render.max-per-ip-per-min:120}") int renderMaxPerIp,
                               @org.springframework.beans.factory.annotation.Value("${luke.embed.submit.max-per-token-per-min:20}") int submitMaxPerToken,
                               @org.springframework.beans.factory.annotation.Value("${luke.embed.submit.max-per-ip-per-min:40}") int submitMaxPerIp) {
        this.resolver = resolver;
        this.versions = versions;
        this.instances = instances;
        this.submissions = submissions;
        this.rateLimiter = rateLimiter;
        this.branding = branding;
        this.turnstile = turnstile;
        this.renderMaxPerToken = renderMaxPerToken;
        this.renderMaxPerIp = renderMaxPerIp;
        this.submitMaxPerToken = submitMaxPerToken;
        this.submitMaxPerIp = submitMaxPerIp;
    }

    /** {@code attachmentRef} is the client-minted high-entropy processRef the browser uploaded
     *  attachments under (Flow-A); on submit we bind them to the created instance so they're captured
     *  in the {@code formMetaData} snapshot. Optional (absent → no attachments).
     *
     *  <p>{@code consentAgreed} is the filler ticking the form's agreement. Only the ACTION comes from
     *  here — the wording recorded is read server-side from the served version's schema. Absent is
     *  treated as "not agreed", so a consent-requiring form refuses an old client rather than recording
     *  a submission with no evidence.
     *
     *  <p>{@code captchaToken} is the Cloudflare Turnstile challenge response the widget produced. It is
     *  single-use and short-lived, and is verified server-side against Cloudflare — the client's claim
     *  that it solved a challenge is worth nothing on its own. Absent is treated as "did not attempt",
     *  which is refused in every profile. */
    public record SubmitBody(Map<String, Object> data, String attachmentRef, Boolean consentAgreed,
                             String captchaToken) {}

    /** Public render: resolve the token to the form's published schema. */
    @GetMapping("/{token}")
    public Map<String, Object> render(@PathVariable String token, HttpServletRequest request,
                                      HttpServletResponse response) {
        // Throttle the UNAUTHENTICATED render (#55): each call does a token verify + 2 DB lookups +
        // schema load, so an uncapped GET is cheap resource-exhaustion + cross-token schema scraping.
        rateLimiter.enforce("embed-render-ip:" + clientIp(request), renderMaxPerIp, response);
        rateLimiter.enforce("embed-render-t:" + token, renderMaxPerToken, response);
        EmbedFormResolver.Resolved resolved = resolver.resolve(token);
        FormDefinition form = resolved.form();
        // AUTO → the published version (publishing reaches every embed instantly); PINNED → the version
        // the author pinned, so a publish does not change what fillers see until they update the embed.
        int v = embedVersion(form);
        String schema = versions.findByFormIdAndVersion(form.getId(), v)
                .map(FormVersion::getSchema)
                .orElseThrow(() -> notFound("This form is no longer available."));
        Map<String, Object> out = new HashMap<>();
        out.put("code", form.getCode());
        out.put("title", form.getName());
        out.put("version", v);
        out.put("schema", schema);
        out.put("allowedEmbedOrigins", form.getAllowedEmbedOrigins()); // null = any site (public default)
        // "Developed at Lukeflow" attribution. Resolved SERVER-SIDE against the tenant's plan, so a free
        // tenant can't suppress it by editing the payload/DOM contract — the flag the iframe receives is
        // already the effective answer.
        out.put("showBranding", branding.showBadge(resolved.tenantId(), form.isShowBranding()));
        // Turnstile: the page needs the PUBLIC sitekey to mount the widget, and needs to know whether to
        // mount one at all — carried here so a key rotation or an environment difference never requires
        // rebuilding and re-vendoring the embed bundle. The SECRET is never part of any payload.
        out.put("captchaEnabled", turnstile.isEnabled());
        out.put("captchaSitekey", turnstile.isEnabled() ? turnstile.sitekey() : null);
        return out;
    }

    /** Inbound webhook: record a submission as a SUBMITTED instance. */
    @PostMapping("/{token}/submit")
    public Map<String, Object> submit(@PathVariable String token,
                                      @RequestBody(required = false) SubmitBody body,
                                      HttpServletRequest request, HttpServletResponse response) {
        // Per-IP cap first (M5): bounds ALL submit traffic from one source across every token.
        rateLimiter.enforce("embed-submit-ip:" + clientIp(request), submitMaxPerIp, response);
        EmbedFormResolver.Resolved r = resolver.resolve(token);

        // Bot trap (M5): real users never fill the hidden honeypot field. Drop silently — return a
        // success shape so spammers don't learn — checked on the RAW body before validation strips it.
        if (Honeypot.tripped(body != null ? body.data() : null)) {
            return Map.of("ok", true, "instanceId", "", "processStatus", "DROPPED");
        }

        // Captcha AFTER the honeypot and the per-IP cap, deliberately: an obvious bot is dropped
        // without costing us a Cloudflare round-trip, and a flood is already bounded before it can
        // amplify into outbound requests. Before the per-token cap so an unverified caller cannot burn
        // a real form's budget.
        requireCaptcha(body, request);

        rateLimiter.enforce("embed-submit-t:" + token, submitMaxPerToken, response);
        FormDefinition form = r.form();
        // MUST be the same resolution the render used: validating a pinned embed's answers against a
        // newer published schema would drop fields the filler was actually shown.
        int v = embedVersion(form);

        // Server-side backstop (M3): the public submit endpoint cannot trust the client. Validate +
        // clean against the published schema — strip unknown fields, enforce required, bound size,
        // strip control chars — before anything is persisted or a process is started.
        String schema = versions.findByFormIdAndVersion(form.getId(), v).map(FormVersion::getSchema).orElse(null);
        Map<String, Object> cleaned = SubmissionValidator.clean(schema, body != null ? body.data() : null);

        FormInstance inst = new FormInstance();
        inst.setTenantId(r.tenantId());
        inst.setToken(uniqueToken());
        inst.setDefinitionCode(form.getCode());
        inst.setVersion(v);
        inst.setState(FormInstanceStates.SUBMITTED);
        inst.setData(cleaned);
        inst.setContext(new HashMap<>(Map.of("source", "embed")));
        inst.setSubmittedAt(LocalDateTime.now());

        // Persist the submission + enqueue the process start in ONE transaction
        // (durable, no HTTP hop). The outbox consumer starts the process off-thread. Passing the
        // attachmentRef binds this session's uploads to the instance BEFORE the formMetaData snapshot.
        // The SubmissionSource records who submitted from where — evidence for enforceability.
        submissions.submit(inst, null, body != null ? body.attachmentRef() : null,
                SubmissionSource.from(request, SubmissionSource.VIA_EMBED,
                        body != null && Boolean.TRUE.equals(body.consentAgreed())));
        return Map.of("ok", true, "instanceId", inst.getId(), "processStatus", "QUEUED");
    }

    /**
     * Demand a solved Turnstile challenge, or refuse the submission.
     *
     * <p>REJECTED — Cloudflare said no, or no token was sent — is refused in EVERY profile: a client
     * that did not attempt the challenge is not a client we accept writes from. UNREACHABLE is the only
     * outcome that bends, and only outside {@code prod}: a Cloudflare outage should not break dev/qa,
     * but in production an unverifiable anonymous submission is not worth accepting.
     *
     * <p>The 400 says nothing about WHY. Cloudflare's error codes tell an abuser which of their attempts
     * is closest to working, so they are logged and never returned.
     */
    private void requireCaptcha(SubmitBody body, HttpServletRequest request) {
        if (!turnstile.isEnabled()) return;
        String captchaToken = body != null ? body.captchaToken() : null;
        TurnstileVerifier.Outcome outcome = turnstile.verify(captchaToken, SubmissionSource.clientIp(request));
        if (outcome == TurnstileVerifier.Outcome.VERIFIED) return;
        if (outcome == TurnstileVerifier.Outcome.UNREACHABLE && !turnstile.failClosed()) {
            log.warn("Turnstile was unreachable; ACCEPTING the submission (fail-open outside the '{}' "
                    + "profile). Set the profile in production to refuse instead.",
                    com.luke.engine.config.StrictProfile.PROFILE);
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Could not verify that you're human. Please try again.");
    }

    /* ── helpers ────────────────────────────────────────────── */

    private String uniqueToken() {
        String token = FormSupport.generateToken();
        while (instances.existsByToken(token)) token = FormSupport.generateToken();
        return token;
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    /** Best-effort client IP for rate-limiting. Prefers X-Real-Client-IP — the gateway resolves the
     *  true client IP spoof-resistantly (trusted-proxy-hops from the right) and stamps it, stripping
     *  any client-supplied value first, so when this surface is reached only through the gateway it is
     *  authoritative and NOT spoofable. Falls back to the left-most X-Forwarded-For hop, then X-Real-IP,
     *  then the socket. Not used for authz — spoofing only changes which bucket the caller lands in. */
    /** The version this form's embeds serve (AUTO → published, PINNED → the pin). A pin that points at a
     *  version with no stored schema falls back to published, so an embed can never go dark. */
    private int embedVersion(FormDefinition form) {
        Integer v = EmbedVersions.resolve(form,
                pinned -> versions.findByFormIdAndVersion(form.getId(), pinned).isPresent());
        if (v == null) throw notFound("This form is not published.");
        return v;
    }

    /** The rate-limit bucket key. Delegates to {@link SubmissionSource#clientIp} so throttling and the
     *  recorded submission provenance can never disagree about who a caller is. */
    private static String clientIp(HttpServletRequest req) {
        String ip = SubmissionSource.clientIp(req);
        return ip == null ? "unknown" : ip;
    }
}
