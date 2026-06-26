package com.luke.engine.capability.form;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final EmbedTokens embedTokens;
    private final FormDefinitionRepository forms;
    private final FormVersionRepository versions;
    private final FormInstanceRepository instances;
    private final FormSubmissionService submissions;

    public FormEmbedController(EmbedTokens embedTokens, FormDefinitionRepository forms,
                               FormVersionRepository versions, FormInstanceRepository instances,
                               FormSubmissionService submissions) {
        this.embedTokens = embedTokens;
        this.forms = forms;
        this.versions = versions;
        this.instances = instances;
        this.submissions = submissions;
    }

    public record SubmitBody(Map<String, Object> data) {}

    /** Public render: resolve the token to the form's published schema. */
    @GetMapping("/{token}")
    public Map<String, Object> render(@PathVariable String token) {
        EmbedTokens.EmbedRef ref = resolve(token);
        FormDefinition form = publishedForm(ref);
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
                                      HttpServletRequest request) {
        // Per-IP cap first (M5): bounds ALL submit traffic from one source across every token.
        rateLimit("ip:" + clientIp(request), MAX_PER_IP_PER_MIN);
        EmbedTokens.EmbedRef ref = resolve(token);

        // Bot trap (M5): real users never fill the hidden honeypot field. Drop silently — return a
        // success shape so spammers don't learn — checked on the RAW body before validation strips it.
        if (Honeypot.tripped(body != null ? body.data() : null)) {
            return Map.of("ok", true, "instanceId", "", "processStatus", "DROPPED");
        }

        rateLimit("t:" + token, MAX_PER_TOKEN_PER_MIN);
        FormDefinition form = publishedForm(ref);
        int v = form.getPublishedVersion();

        // Server-side backstop (M3): the public submit endpoint cannot trust the client. Validate +
        // clean against the published schema — strip unknown fields, enforce required, bound size,
        // strip control chars — before anything is persisted or a process is started.
        String schema = versions.findByFormIdAndVersion(form.getId(), v).map(FormVersion::getSchema).orElse(null);
        Map<String, Object> cleaned = SubmissionValidator.clean(schema, body != null ? body.data() : null);

        FormInstance inst = new FormInstance();
        inst.setTenantId(ref.tenantId());
        inst.setToken(uniqueToken());
        inst.setDefinitionCode(form.getCode());
        inst.setVersion(v);
        inst.setState(FormInstanceStates.SUBMITTED);
        inst.setData(cleaned);
        inst.setContext(new HashMap<>(Map.of("source", "embed")));
        inst.setSubmittedAt(LocalDateTime.now());

        // Persist the submission + enqueue the process start in ONE transaction
        // (durable, no HTTP hop). The outbox consumer starts the process off-thread.
        submissions.submit(inst, null);
        return Map.of("ok", true, "instanceId", inst.getId(), "processStatus", "QUEUED");
    }

    /* ── helpers ────────────────────────────────────────────── */

    private EmbedTokens.EmbedRef resolve(String token) {
        try {
            return embedTokens.verify(token);
        } catch (IllegalArgumentException e) {
            throw notFound("Unknown or invalid form link."); // don't leak token validity
        }
    }

    private FormDefinition publishedForm(EmbedTokens.EmbedRef ref) {
        FormDefinition form = forms.findByTenantIdAndCode(ref.tenantId(), ref.code())
                .filter(f -> f.getDeletedAt() == null)
                .orElseThrow(() -> notFound("This form is no longer available."));
        if (form.getPublishedVersion() == null) {
            throw notFound("This form is not published.");
        }
        // Revocation (M4): a token minted before the form's embed key was rotated is dead.
        if (ref.keyVersion() != form.getEmbedKeyVersion()) {
            throw notFound("Unknown or invalid form link.");
        }
        return form;
    }

    private String uniqueToken() {
        String token = FormSupport.generateToken();
        while (instances.existsByToken(token)) token = FormSupport.generateToken();
        return token;
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    // ── Abuse guards (M5): fixed 1-minute windows, keyed per token AND per client IP ─────────
    private static final int MAX_PER_TOKEN_PER_MIN = 20;
    private static final int MAX_PER_IP_PER_MIN = 40;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private void rateLimit(String key, int max) {
        long minute = System.currentTimeMillis() / 60_000L;
        Window w = windows.compute(key, (k, cur) ->
                (cur == null || cur.minute != minute) ? new Window(minute) : cur);
        if (w.count.incrementAndGet() > max) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many submissions, try again shortly.");
        }
        // Bound growth by evicting only STALE windows (from earlier minutes), never the current
        // minute's counters. Clearing the whole map would reset every counter at once — a global
        // rate-limit bypass an attacker could trigger by flooding fresh keys past the threshold.
        if (windows.size() > 50_000) {
            windows.values().removeIf(win -> win.minute != minute);
        }
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

    private static final class Window {
        final long minute;
        final AtomicInteger count = new AtomicInteger(0);
        Window(long minute) { this.minute = minute; }
    }
}
