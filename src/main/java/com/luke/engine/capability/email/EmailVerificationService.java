package com.luke.engine.capability.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase-1 org verification: prove a tenant controls an official company mailbox by
 * mailing a one-time passcode and matching the mailbox domain against the org name.
 * On a correct code the tenant's Postmark server is auto-provisioned (see
 * {@link EmailServerService#completeVerification}).
 *
 * <p>The OTP email is sent from the <em>platform</em> sender via the fallback token,
 * NOT the tenant's server — the tenant isn't provisioned yet, so per-tenant sender
 * enforcement can't apply here. Codes are stored only as salted hashes, are
 * short-lived, and are attempt-limited.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final EmailVerificationRepository verifications;
    private final EmailServerService servers;
    private final PostmarkClient postmark;

    @Value("${luke.email.verification.code-length:6}")
    private int codeLength;

    @Value("${luke.email.verification.ttl-seconds:600}")
    private long ttlSeconds;

    @Value("${luke.email.verification.max-attempts:5}")
    private int maxAttempts;

    /** Platform sender for OTP mail — must be a verified Postmark signature on the fallback server. */
    @Value("${luke.email.postmark.default-from:}")
    private String platformFrom;

    /** Fallback Postmark server token used to send the OTP (tenant has no server yet). */
    @Value("${luke.email.postmark.server-token:}")
    private String platformToken;

    @Value("${luke.email.postmark.message-stream:outbound}")
    private String messageStream;

    /** Postmark alias of the published OTP template (see {@link OtpTemplateInstaller}). */
    @Value("${luke.email.otp.template-alias:" + OtpEmailTemplate.DEFAULT_ALIAS + "}")
    private String templateAlias;

    /** Product/brand name merged into the OTP email. */
    @Value("${luke.email.otp.product-name:Lukeflow}")
    private String productName;

    public EmailVerificationService(EmailVerificationRepository verifications,
                                    EmailServerService servers,
                                    PostmarkClient postmark) {
        this.verifications = verifications;
        this.servers = servers;
        this.postmark = postmark;
    }

    /* ── results ────────────────────────────────────────────── */

    /** Public view of a verification (never the code/hash). */
    public record VerificationView(String id, String status, String email, String domain,
                                   String orgName, int attemptsRemaining, LocalDateTime expiresAt) {}

    /** Verify outcome: the verification + the auto-provisioned server (or a provisioning error). */
    public record VerifyResult(VerificationView verification, EmailServer server, String provisioningError) {}

    /* ── start ──────────────────────────────────────────────── */

    /**
     * Begin verification: validate the email + name↔domain match, mint an OTP, mail
     * it, and supersede any earlier pending challenge for this tenant.
     */
    public VerificationView start(String tenantId, String orgName, String email) {
        if (isBlank(orgName)) throw bad("orgName is required");
        if (isBlank(email) || !OrgDomainMatcher.looksLikeEmail(email)) {
            throw bad("a valid official email is required");
        }
        if (isBlank(platformToken) || isBlank(platformFrom)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Verification email is not configured (set POSTMARK_SERVER_TOKEN and EMAIL_DEFAULT_FROM)");
        }
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
        String domain = OrgDomainMatcher.domainOf(normalizedEmail);

        if (OrgDomainMatcher.isFreeProvider(domain)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Use your company email, not a personal address (" + domain + ")");
        }
        OrgDomainMatcher.MatchResult match = OrgDomainMatcher.match(orgName, domain);
        if (!match.ok()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Email domain " + domain + " does not match the organization name '" + orgName + "'");
        }

        // Supersede any earlier pending challenge so only the newest code is live.
        List<EmailVerification> pending = verifications.findByTenantIdAndStatus(tenantId, EmailVerification.PENDING);
        for (EmailVerification old : pending) {
            old.setStatus(EmailVerification.EXPIRED);
        }
        verifications.saveAll(pending);

        String code = generateCode();
        String salt = randomHex(16);

        EmailVerification v = new EmailVerification();
        v.setTenantId(tenantId);
        v.setOrgName(orgName.trim());
        v.setEmail(normalizedEmail);
        v.setDomain(domain);
        v.setStatus(EmailVerification.PENDING);
        v.setCodeSalt(salt);
        v.setCodeHash(hash(salt, code));
        v.setExpiresAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        verifications.save(v);

        sendCode(normalizedEmail, orgName.trim(), code);
        log.info("Started email verification for tenant {} ({} → {})", tenantId, orgName, domain);
        return view(v);
    }

    /* ── verify ─────────────────────────────────────────────── */

    /**
     * Check a submitted code against the tenant's active challenge. On success, mark
     * it VERIFIED and auto-provision the tenant's Postmark server. A provisioning
     * failure does not lose the proof — the verification stays VERIFIED and the error
     * is returned so provisioning can be retried.
     */
    public VerifyResult verify(String tenantId, String code) {
        EmailVerification v = verifications
                .findFirstByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, EmailVerification.PENDING)
                .orElseThrow(() -> bad("No pending verification — start one first"));

        if (LocalDateTime.now().isAfter(v.getExpiresAt())) {
            v.setStatus(EmailVerification.EXPIRED);
            verifications.save(v);
            throw new ResponseStatusException(HttpStatus.GONE, "The code has expired — request a new one");
        }
        if (isBlank(code) || !constantTimeEquals(hash(v.getCodeSalt(), code.trim()), v.getCodeHash())) {
            v.setAttempts(v.getAttempts() + 1);
            if (v.getAttempts() >= maxAttempts) {
                v.setStatus(EmailVerification.FAILED);
                verifications.save(v);
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many incorrect attempts — request a new code");
            }
            verifications.save(v);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Incorrect code — " + (maxAttempts - v.getAttempts()) + " attempt(s) left");
        }

        v.setStatus(EmailVerification.VERIFIED);
        v.setVerifiedAt(LocalDateTime.now());
        verifications.save(v);

        // Proof is secured; provisioning is best-effort from here so a Postmark/config
        // hiccup doesn't discard a valid verification.
        EmailServer server = null;
        String provisioningError = null;
        try {
            server = servers.completeVerification(tenantId, v.getOrgName(), v.getEmail());
        } catch (ResponseStatusException e) {
            provisioningError = e.getReason();
            log.warn("Verified tenant {} but auto-provision failed: {}", tenantId, provisioningError);
        }
        return new VerifyResult(view(v), server, provisioningError);
    }

    /** The tenant's latest verification (any status), for status polling. */
    public VerificationView current(String tenantId) {
        return verifications.findFirstByTenantIdOrderByCreatedAtDesc(tenantId)
                .map(this::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No verification has been started for this tenant"));
    }

    /* ── helpers ────────────────────────────────────────────── */

    private void sendCode(String email, String orgName, String code) {
        Map<String, String> model = Map.of(
                "code", code,
                "org_name", orgName,
                "expiry_minutes", String.valueOf(ttlSeconds / 60),
                "product_name", productName);

        // Preferred path: send the stored Postmark template by alias (published by
        // OtpTemplateInstaller). Postmark fills the {{var}} fields from TemplateModel.
        Map<String, Object> templated = PostmarkClient.body();
        PostmarkClient.put(templated, "From", platformFrom);
        PostmarkClient.put(templated, "To", email);
        PostmarkClient.put(templated, "TemplateAlias", templateAlias);
        templated.put("TemplateModel", model);
        PostmarkClient.put(templated, "MessageStream", messageStream);

        PostmarkClient.SendResult res = postmark.sendTemplate(platformToken, templated);
        if (res.ok()) return;

        // Fallback: the stored template may be missing (token added after boot, alias
        // deleted, publish failed). Render the same template inline so OTP still goes
        // out. A genuine delivery failure (bad token/sender) surfaces as a 502.
        log.warn("OTP template send failed ({}); falling back to an inline send", res.message());
        Map<String, Object> inline = PostmarkClient.body();
        PostmarkClient.put(inline, "From", platformFrom);
        PostmarkClient.put(inline, "To", email);
        PostmarkClient.put(inline, "Subject", OtpEmailTemplate.render(OtpEmailTemplate.SUBJECT, model, false));
        PostmarkClient.put(inline, "HtmlBody", OtpEmailTemplate.render(OtpEmailTemplate.HTML, model, true));
        PostmarkClient.put(inline, "TextBody", OtpEmailTemplate.render(OtpEmailTemplate.TEXT, model, false));
        PostmarkClient.put(inline, "MessageStream", messageStream);

        PostmarkClient.SendResult fallback = postmark.send(platformToken, inline);
        if (!fallback.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not send the verification email: " + fallback.message());
        }
    }

    private VerificationView view(EmailVerification v) {
        int remaining = Math.max(0, maxAttempts - v.getAttempts());
        return new VerificationView(v.getId(), v.getStatus(), v.getEmail(), v.getDomain(),
                v.getOrgName(), remaining, v.getExpiresAt());
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, codeLength);
        String fmt = "%0" + codeLength + "d";
        return String.format(fmt, RNG.nextInt(bound));
    }

    private static String hash(String salt, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(HexFormat.of().parseHex(salt));
            md.update(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
