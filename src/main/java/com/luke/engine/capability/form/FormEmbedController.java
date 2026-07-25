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

    private final EmbedFormResolver resolver;
    private final FormVersionRepository versions;
    private final FormInstanceRepository instances;
    private final FormSubmissionService submissions;
    private final com.luke.engine.web.FixedWindowRateLimiter rateLimiter;

    // Configurable per-minute caps for the PUBLIC embed surface (#55), keyed per token AND per IP.
    private final int renderMaxPerToken;
    private final int renderMaxPerIp;
    private final int submitMaxPerToken;
    private final int submitMaxPerIp;

    public FormEmbedController(EmbedFormResolver resolver, FormVersionRepository versions,
                               FormInstanceRepository instances, FormSubmissionService submissions,
                               com.luke.engine.web.FixedWindowRateLimiter rateLimiter,
                               @org.springframework.beans.factory.annotation.Value("${luke.embed.render.max-per-token-per-min:60}") int renderMaxPerToken,
                               @org.springframework.beans.factory.annotation.Value("${luke.embed.render.max-per-ip-per-min:120}") int renderMaxPerIp,
                               @org.springframework.beans.factory.annotation.Value("${luke.embed.submit.max-per-token-per-min:20}") int submitMaxPerToken,
                               @org.springframework.beans.factory.annotation.Value("${luke.embed.submit.max-per-ip-per-min:40}") int submitMaxPerIp) {
        this.resolver = resolver;
        this.versions = versions;
        this.instances = instances;
        this.submissions = submissions;
        this.rateLimiter = rateLimiter;
        this.renderMaxPerToken = renderMaxPerToken;
        this.renderMaxPerIp = renderMaxPerIp;
        this.submitMaxPerToken = submitMaxPerToken;
        this.submitMaxPerIp = submitMaxPerIp;
    }

    /** {@code attachmentRef} is the client-minted high-entropy processRef the browser uploaded
     *  attachments under (Flow-A); on submit we bind them to the created instance so they're captured
     *  in the {@code formMetaData} snapshot. Optional (absent → no attachments). */
    public record SubmitBody(Map<String, Object> data, String attachmentRef) {}

    /** Public render: resolve the token to the form's published schema. */
    @GetMapping("/{token}")
    public Map<String, Object> render(@PathVariable String token, HttpServletRequest request,
                                      HttpServletResponse response) {
        // Throttle the UNAUTHENTICATED render (#55): each call does a token verify + 2 DB lookups +
        // schema load, so an uncapped GET is cheap resource-exhaustion + cross-token schema scraping.
        rateLimiter.enforce("embed-render-ip:" + clientIp(request), renderMaxPerIp, response);
        rateLimiter.enforce("embed-render-t:" + token, renderMaxPerToken, response);
        FormDefinition form = resolver.resolve(token).form();
        int v = form.getPublishedVersion();
        String schema = versions.findByFormIdAndVersion(form.getId(), v)
                .map(FormVersion::getSchema)
                .orElseThrow(() -> notFound("This form is no longer available."));
        Map<String, Object> out = new HashMap<>();
        out.put("code", form.getCode());
        out.put("title", form.getName());
        out.put("version", v);
        out.put("schema", schema);
        out.put("allowedEmbedOrigins", form.getAllowedEmbedOrigins()); // null = any site (public default)
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

        rateLimiter.enforce("embed-submit-t:" + token, submitMaxPerToken, response);
        FormDefinition form = r.form();
        int v = form.getPublishedVersion();

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
        submissions.submit(inst, null, body != null ? body.attachmentRef() : null);
        return Map.of("ok", true, "instanceId", inst.getId(), "processStatus", "QUEUED");
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

    /** Best-effort client IP for rate-limiting: the left-most X-Forwarded-For hop (set by the
     *  gateway/edge), then X-Real-IP, then the socket address. Not used for authz — spoofing it only
     *  changes which bucket the caller rate-limits themselves into. */
    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        String remote = req.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }
}
