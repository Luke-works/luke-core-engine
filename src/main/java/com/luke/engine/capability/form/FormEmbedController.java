package com.luke.engine.capability.form;

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
        return out;
    }

    /** Inbound webhook: record a submission as a SUBMITTED instance. */
    @PostMapping("/{token}/submit")
    public Map<String, Object> submit(@PathVariable String token, @RequestBody(required = false) SubmitBody body) {
        EmbedTokens.EmbedRef ref = resolve(token);
        rateLimit(token);
        FormDefinition form = publishedForm(ref);
        int v = form.getPublishedVersion();

        FormInstance inst = new FormInstance();
        inst.setTenantId(ref.tenantId());
        inst.setToken(uniqueToken());
        inst.setDefinitionCode(form.getCode());
        inst.setVersion(v);
        inst.setState(FormInstanceStates.SUBMITTED);
        inst.setData(body != null ? body.data() : Map.of());
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

    // ── Minimal abuse guard: fixed 1-minute window per token ─────────────────
    private static final int MAX_PER_MINUTE = 20;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private void rateLimit(String token) {
        long minute = System.currentTimeMillis() / 60_000L;
        Window w = windows.compute(token, (k, cur) ->
                (cur == null || cur.minute != minute) ? new Window(minute) : cur);
        if (w.count.incrementAndGet() > MAX_PER_MINUTE) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many submissions, try again shortly.");
        }
        // Bound growth by evicting only STALE windows (from earlier minutes), never
        // the current minute's counters. Clearing the whole map (the old behavior)
        // reset every token's counter at once — a global rate-limit bypass any
        // attacker could trigger by flooding fresh tokens past the threshold.
        if (windows.size() > 10_000) {
            windows.values().removeIf(win -> win.minute != minute);
        }
    }

    private static final class Window {
        final long minute;
        final AtomicInteger count = new AtomicInteger(0);
        Window(long minute) { this.minute = minute; }
    }
}
