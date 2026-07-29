package com.luke.engine.capability.form;

import com.luke.engine.capability.email.EmailRequest;
import com.luke.engine.capability.email.EmailService;
import com.luke.engine.recipient.PortalAccessTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The public, OTP-gated outbound fill surface (Phase 2). The recipient opens {@code /respond/:token},
 * requests a one-time code (mailed to the PREPARER-asserted recipient email — a recipient-supplied
 * contact would be theater), verifies it for a short-lived access token, then renders / autosaves /
 * submits the prefilled instance. The opaque instance token + the OTP together are the auth — this
 * path never sees a tenant header.
 *
 * <p>Mirrors the signing capability's public-instance flow. Email delivery is best-effort at request
 * time; the code is always stored (salted-hashed, expiring, attempt-capped) so verification works.
 */
@Service
public class PublicFormInstanceService {

    private static final Logger log = LoggerFactory.getLogger(PublicFormInstanceService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final long OTP_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_ATTEMPTS = 5;

    private final FormInstanceRepository instances;
    private final FormRecipientOtpRepository otps;
    private final FormDefinitionRepository forms;
    private final FormVersionRepository versions;
    private final EmailService emails;
    private final FormSubmissionService submissions;
    private final RecipientAccessTokens accessTokens;
    private final PortalAccessTokens portalTokens;

    public PublicFormInstanceService(FormInstanceRepository instances, FormRecipientOtpRepository otps,
            FormDefinitionRepository forms, FormVersionRepository versions, EmailService emails,
            FormSubmissionService submissions, RecipientAccessTokens accessTokens,
            PortalAccessTokens portalTokens) {
        this.instances = instances;
        this.otps = otps;
        this.forms = forms;
        this.versions = versions;
        this.emails = emails;
        this.submissions = submissions;
        this.accessTokens = accessTokens;
        this.portalTokens = portalTokens;
    }

    /** Mail a fresh OTP to the instance's recipient. Returns the (best-effort) email status. */
    @Transactional
    public Map<String, Object> requestOtp(String token) {
        FormInstance inst = openInstance(token);
        String email = recipientEmail(inst);
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This form has no recipient email to verify.");
        }
        FormRecipientOtp otp = otps.findByInstanceId(inst.getId()).orElseGet(() -> {
            FormRecipientOtp o = new FormRecipientOtp();
            o.setInstanceId(inst.getId());
            return o;
        });
        // Resend throttle: don't re-mail (or re-mint) more than once per 30s per instance.
        if (otp.getId() != null && otp.getCreatedAt() != null
                && otp.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(30))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait a moment before requesting another code.");
        }
        String code = String.format("%06d", RNG.nextInt(1_000_000));
        String salt = randomSalt();
        otp.setCodeHash(hash(code, salt));
        otp.setCodeSalt(salt);
        otp.setAttempts(0);
        otp.setCreatedAt(LocalDateTime.now());
        otp.setExpiresAt(LocalDateTime.now().plusSeconds(OTP_TTL_MS / 1000));
        otps.save(otp);

        String emailStatus = deliver(inst.getTenantId(), email, code);
        return Map.of("ok", true, "emailStatus", emailStatus, "sentTo", mask(email));
    }

    /** Verify a code; on success mark the instance OPENED and mint a short-lived access token. */
    @Transactional
    public Map<String, Object> verify(String token, String code) {
        FormInstance inst = openInstance(token);
        FormRecipientOtp otp = otps.findByInstanceId(inst.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request a code first."));
        if (LocalDateTime.now().isAfter(otp.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "That code has expired — request a new one.");
        }
        if (otp.getAttempts() >= MAX_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts — request a new code.");
        }
        boolean ok = code != null
                && MessageDigest.isEqual(hash(code.trim(), otp.getCodeSalt()).getBytes(StandardCharsets.UTF_8),
                        otp.getCodeHash().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            otp.setAttempts(otp.getAttempts() + 1);
            otps.save(otp);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "That code isn't right.");
        }
        otps.delete(otp); // single-use
        if (FormInstanceStates.SENT.equals(inst.getState()) || FormInstanceStates.CREATED.equals(inst.getState())) {
            inst.setState(FormInstanceStates.OPENED);
            instances.save(inst);
        }
        return Map.of("accessToken", accessTokens.sign(token, System.currentTimeMillis()));
    }

    /** The render payload for an authenticated recipient: schema + prefill + any saved data. */
    @Transactional(readOnly = true)
    public Map<String, Object> render(String token, String accessToken) {
        FormInstance inst = authorize(token, accessToken);
        FormDefinition form = forms.findByTenantIdAndCode(inst.getTenantId(), inst.getDefinitionCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Form not found."));
        String schema = versions.findByFormIdAndVersion(form.getId(), inst.getVersion())
                .map(FormVersion::getSchema)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Form version not found."));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", form.getCode());
        out.put("name", form.getName());
        out.put("version", inst.getVersion());
        out.put("schema", schema);
        out.put("prefill", inst.getPrefill() != null ? inst.getPrefill() : Map.of());
        out.put("data", inst.getData() != null ? inst.getData() : Map.of());
        out.put("outboundRoles", parseRoles(form.getOutboundRolesJson()));
        out.put("recipient", recipientView(inst));
        out.put("state", inst.getState());
        return out;
    }

    /** Autosave the recipient's in-progress answers. */
    @Transactional
    public void save(String token, String accessToken, Map<String, Object> data) {
        FormInstance inst = authorize(token, accessToken);
        Map<String, Object> merged = new HashMap<>(inst.getData() != null ? inst.getData() : Map.of());
        merged.putAll(recipientWritable(inst, data));
        inst.setData(merged);
        if (!FormInstanceStates.IN_PROGRESS.equals(inst.getState())) inst.setState(FormInstanceStates.IN_PROGRESS);
        instances.save(inst);
    }

    /** Final submit — flips to SUBMITTED, enqueues the process start, emits the forms event. */
    @Transactional
    public Map<String, Object> submit(String token, String accessToken, Map<String, Object> data) {
        FormInstance inst = authorize(token, accessToken);
        submissions.submit(inst, recipientWritable(inst, data));
        return Map.of("ok", true, "instanceId", inst.getId(), "state", inst.getState());
    }

    /**
     * Drop the fields this recipient does not own before their answers touch the instance.
     *
     * <p>An outbound form is filled by TWO people: the preparer supplies some fields up front (a
     * quoted price, a case reference, a policy number) and the recipient answers the rest. The
     * recipient's browser renders the preparer's fields read-only — but "read-only" is a rendering
     * decision, and this endpoint is reachable without a browser. Without this, anyone holding a
     * valid recipient link could POST a new value for a preparer-owned field and silently rewrite
     * the terms of what they were sent.
     *
     * <p>A field is preparer-owned when the form's outbound role map says {@code PREPARER}, or —
     * for forms authored before roles existed — when the schema marks it {@code disabled}, which is
     * how preparer fields have always been expressed. {@code EITHER} stays recipient-writable: the
     * preparer may seed it, the recipient may still change it.
     *
     * <p>Rejected keys are dropped silently rather than 400'd: a legitimate client never sends them
     * (the fields are disabled in its DOM), so a request carrying them is either tampering or a
     * stale tab, and neither deserves a descriptive error.
     */
    private Map<String, Object> recipientWritable(FormInstance inst, Map<String, Object> data) {
        if (data == null || data.isEmpty()) return Map.of();
        FormDefinition form = forms.findByTenantIdAndCode(inst.getTenantId(), inst.getDefinitionCode()).orElse(null);
        if (form == null) return data; // unknown contract — cleaning happens downstream regardless

        Set<String> preparerOwned = new HashSet<>();
        for (Map.Entry<String, Object> e : parseRoles(form.getOutboundRolesJson()).entrySet()) {
            if ("PREPARER".equals(String.valueOf(e.getValue()))) preparerOwned.add(e.getKey());
        }
        String schema = versions.findByFormIdAndVersion(form.getId(), inst.getVersion())
                .map(FormVersion::getSchema).orElse(null);
        for (FormSupport.FieldRule f : FormSupport.extractFieldRules(schema)) {
            if (f.attributes().path("disabled").asBoolean(false)) preparerOwned.add(f.key());
        }
        if (preparerOwned.isEmpty()) return data;

        Map<String, Object> out = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (preparerOwned.contains(e.getKey())) dropped.add(e.getKey());
            else out.put(e.getKey(), e.getValue());
        }
        if (!dropped.isEmpty()) {
            log.info("Recipient write to preparer-owned field(s) ignored on instance {} (tenant {}): {}",
                    inst.getId(), inst.getTenantId(), dropped);
        }
        return out;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Resolve an OPEN instance by token, or the right 4xx (never leaking whether the token exists). */
    private FormInstance openInstance(String token) {
        FormInstance inst = instances.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This form link isn't valid."));
        if (inst.isExpired()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This form link has expired.");
        }
        if (!FormInstanceStates.isOpen(inst.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This form is no longer open.");
        }
        return inst;
    }

    /**
     * Authorise a fill/save/submit against this instance token. Accepts EITHER of two account-less
     * sessions: the per-instance recipient token minted by the {@code /respond} OTP flow, OR the
     * email-scoped PORTAL session (which authorises any open instance whose recipient email + tenant
     * match). The instance must still be open in both cases.
     */
    private FormInstance authorize(String token, String accessToken) {
        long now = System.currentTimeMillis();
        // 1) Per-instance recipient token (the single-link /respond flow).
        try {
            if (token.equals(accessTokens.verify(accessToken, now))) return openInstance(token);
        } catch (IllegalArgumentException ignore) {
            /* not an instance token — try the portal session below */
        }
        // 2) Email-scoped portal session (authenticate-once portal).
        try {
            PortalAccessTokens.PortalRef ref = portalTokens.verify(accessToken, now);
            FormInstance inst = openInstance(token);
            String email = recipientEmail(inst);
            if (email != null && ref.tenantId().equals(inst.getTenantId()) && ref.email().equalsIgnoreCase(email)) {
                return inst;
            }
        } catch (IllegalArgumentException ignore) {
            /* not a portal session either → fall through to 401 */
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verify your email to continue.");
    }

    private String deliver(String tenantId, String email, String code) {
        try {
            String subject = "Your form verification code: " + code;
            String html = "<p>Your one-time code is:</p><p style=\"font-size:24px;font-weight:700;letter-spacing:3px\">"
                    + code + "</p><p>It expires in 10 minutes.</p>";
            String text = "Your one-time code is: " + code + "\nIt expires in 10 minutes.\n";
            EmailRequest req = new EmailRequest(
                    null, email, null, null, null, subject, html, text,
                    null, null, null, "form-recipient-otp", null, null, null);
            // OTP must confirm delivery inline (the recipient is waiting on the code), so send
            // synchronously rather than queueing (#59).
            return String.valueOf(emails.sendRawSync(tenantId, null, req).getStatus());
        } catch (RuntimeException e) {
            log.warn("Recipient OTP email failed for {}: {}", mask(email), e.getMessage());
            return "FAILED";
        }
    }

    private static String recipientEmail(FormInstance inst) {
        Map<String, Object> r = inst.getRecipient();
        Object e = r == null ? null : r.get("email");
        return e == null || e.toString().isBlank() ? null : e.toString().trim();
    }

    private static Map<String, Object> recipientView(FormInstance inst) {
        Map<String, Object> r = inst.getRecipient();
        Map<String, Object> out = new LinkedHashMap<>();
        if (r != null) {
            out.put("firstName", r.get("firstName"));
            out.put("lastName", r.get("lastName"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseRoles(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String randomSalt() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    static String hash(String code, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest((salt + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hash failure", e);
        }
    }

    /** Mask an email for echo-back: j***@acme.com. */
    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }
}
