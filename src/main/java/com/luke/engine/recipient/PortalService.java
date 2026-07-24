package com.luke.engine.recipient;

import com.luke.engine.capability.email.EmailRequest;
import com.luke.engine.capability.email.EmailService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The recipient PORTAL — the recipient persona's capability-agnostic hub: "authenticate once
 * (email/SMS OTP or magic link), then see every open item assigned to my email in this tenant."
 * Per-tenant — the {@code /portal/{tenantToken}} handle scopes everything, so a recipient never
 * crosses tenant boundaries and a raw tenant id never appears.
 *
 * <p>This service owns IDENTITY only: an account-less two-step flow keyed by {@code (tenant, email)}
 * — a challenge (code or link) proves email control, then {@link PortalAccessTokens} mints an
 * email-scoped session. The ITEMS come from {@link RecipientItemProvider}s (forms is the first;
 * signatures, documents, … plug in), and each capability still owns/authorises its own open/act
 * surface against that same session. The challenge/magic-link/verify paths never leak whether a
 * given email has any items — the response shape, status, and latency are identical either way.
 */
@Service
public class PortalService {

    private static final Logger log = LoggerFactory.getLogger(PortalService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final long OTP_TTL_MS = 10 * 60 * 1000L;
    private static final long MAGIC_TTL_MS = 15 * 60 * 1000L;
    private static final int MAX_ATTEMPTS = 5;
    private static final int RESEND_THROTTLE_SEC = 30;

    private final PortalOtpRepository otps;
    private final PortalMagicLinkRepository magicLinks;
    private final PortalTenantTokens tenantTokens;
    private final PortalAccessTokens accessTokens;
    private final EmailService emails;
    private final Map<PortalChannel, PortalOtpSender> senders = new EnumMap<>(PortalChannel.class);
    private final List<RecipientItemProvider> providers;
    private final Executor executor;
    private final String recipientBaseUrl;

    public PortalService(PortalOtpRepository otps, PortalMagicLinkRepository magicLinks,
            PortalTenantTokens tenantTokens, PortalAccessTokens accessTokens, EmailService emails,
            List<PortalOtpSender> senderBeans, List<RecipientItemProvider> providers,
            @Qualifier("applicationTaskExecutor") Executor executor,
            @Value("${luke.forms.recipient-base-url:http://localhost:8080}") String recipientBaseUrl) {
        this.otps = otps;
        this.magicLinks = magicLinks;
        this.tenantTokens = tenantTokens;
        this.accessTokens = accessTokens;
        this.emails = emails;
        for (PortalOtpSender s : senderBeans) this.senders.put(s.channel(), s);
        this.providers = providers;
        this.executor = executor;
        this.recipientBaseUrl = stripTrailingSlash(recipientBaseUrl);
    }

    // ── challenge (OTP) ─────────────────────────────────────────────────────────

    /** Issue an OTP over the chosen channel. Always returns the same shape/status/latency whether or
     *  not the email has any items (no existence leak); only actually sends when there is ≥1 item. */
    @Transactional
    public Map<String, Object> challenge(String tenantToken, String email, String channelRaw) {
        String tenantId = tenant(tenantToken);
        String norm = normalizeEmail(email);
        if (norm == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter your email address.");
        PortalChannel channel = PortalChannel.parse(channelRaw);

        // Channel availability is a GLOBAL fact (independent of whether this email has items), so it's
        // safe to report — lets the recipient switch to a working method without leaking anything.
        PortalOtpSender sender = senders.get(channel);
        if (sender == null || !sender.available()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    channel == PortalChannel.SMS
                            ? "Text-message verification isn't available yet. Please use email."
                            : "That verification method isn't available. Please use email.");
        }

        boolean hasItems = hasItems(tenantId, norm);
        String contact = channel == PortalChannel.SMS ? firstPhone(tenantId, norm) : norm;

        // Uniform response — the shape/status/latency below are IDENTICAL whether or not the email has
        // items, whether or not a phone is on file (SMS), and whether or not we're inside the resend
        // window. We only actually mint + send a code on the has-items, not-recently-sent path; every
        // other case is a silent no-op. In particular there is NO distinguishable 429 (a rapid re-request
        // is silently ignored, not rejected) and NO synchronous send (delivery is dispatched off-thread),
        // so an attacker holding the shared tenant handle can't enumerate recipients by status or timing.
        if (hasItems && contact != null) {
            Optional<PortalOtp> existing = otps.findByTenantIdAndRecipientEmail(tenantId, norm);
            boolean throttled = existing
                    .map(o -> o.getCreatedAt() != null
                            && o.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(RESEND_THROTTLE_SEC)))
                    .orElse(false);
            if (!throttled) {
                PortalOtp otp = existing.orElseGet(() -> {
                    PortalOtp o = new PortalOtp();
                    o.setTenantId(tenantId);
                    o.setRecipientEmail(norm);
                    return o;
                });
                String code = String.format("%06d", RNG.nextInt(1_000_000));
                String salt = randomSalt();
                otp.setChannel(channel.name());
                otp.setCodeHash(hash(code, salt));
                otp.setCodeSalt(salt);
                otp.setAttempts(0);
                otp.setCreatedAt(LocalDateTime.now());
                otp.setExpiresAt(LocalDateTime.now().plusSeconds(OTP_TTL_MS / 1000));
                otps.save(otp);
                dispatchSend(sender, tenantId, contact, code);
            }
        }
        return challengeResponse(channel, norm);
    }

    /** Verify an OTP → an email-scoped portal session token. */
    @Transactional
    public Map<String, Object> verify(String tenantToken, String email, String code) {
        String tenantId = tenant(tenantToken);
        String norm = normalizeEmail(email);
        if (norm == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter your email address.");
        // A missing challenge and a wrong code return the SAME 401 — since challenge() only creates a
        // row for an email that has items, distinguishing "no code on file" (would-be 400) from "wrong
        // code" (401) would leak whether the email has any items. Keep them indistinguishable.
        PortalOtp otp = otps.findByTenantIdAndRecipientEmail(tenantId, norm).orElse(null);
        if (otp == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "That code isn't right — request a new one.");
        }
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
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "That code isn't right — request a new one.");
        }
        otps.delete(otp); // single-use
        return Map.of("accessToken", accessTokens.sign(tenantId, norm, System.currentTimeMillis()), "email", mask(norm));
    }

    // ── challenge (magic link) ──────────────────────────────────────────────────

    /** Email a single-use magic link. Generic OK whether or not the email has items (no leak). */
    @Transactional
    public Map<String, Object> requestMagicLink(String tenantToken, String email) {
        String tenantId = tenant(tenantToken);
        String norm = normalizeEmail(email);
        if (norm == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter your email address.");

        // Uniform response (see challenge): identical shape/latency whether or not the email has items
        // or is inside the resend window — only the has-items, not-recently-sent path mints + emails a
        // link; a rapid re-request is a silent no-op (no distinguishable 429), and delivery is off-thread.
        if (hasItems(tenantId, norm)) {
            PortalMagicLink prev = magicLinks
                    .findFirstByTenantIdAndRecipientEmailOrderByCreatedAtDesc(tenantId, norm).orElse(null);
            boolean throttled = prev != null && prev.getCreatedAt() != null
                    && prev.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(RESEND_THROTTLE_SEC));
            if (!throttled) {
                String raw = randomToken();
                magicLinks.deleteByTenantIdAndRecipientEmail(tenantId, norm); // one active link per recipient
                PortalMagicLink link = new PortalMagicLink();
                link.setTenantId(tenantId);
                link.setRecipientEmail(norm);
                link.setTokenHash(sha256(raw));
                link.setCreatedAt(LocalDateTime.now());
                link.setExpiresAt(LocalDateTime.now().plusSeconds(MAGIC_TTL_MS / 1000));
                magicLinks.save(link);
                String url = recipientBaseUrl + "/portal/" + tenantToken + "?lt=" + raw;
                executor.execute(() -> deliverMagicLink(tenantId, norm, url));
            }
        }
        return Map.of("ok", true, "sentTo", mask(norm));
    }

    /** Consume a magic link → an email-scoped portal session token. */
    @Transactional
    public Map<String, Object> consumeMagicLink(String tenantToken, String rawToken) {
        String tenantId = tenant(tenantToken);
        if (rawToken == null || rawToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This link is invalid.");
        }
        PortalMagicLink link = magicLinks.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This link is invalid or has already been used."));
        if (!tenantId.equals(link.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This link is invalid or has already been used.");
        }
        if (link.getConsumedAt() != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This link has already been used.");
        }
        if (LocalDateTime.now().isAfter(link.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has expired — request a new one.");
        }
        String email = link.getRecipientEmail();
        // Atomic single-use: the conditional UPDATE only flips a still-unconsumed row, so of two
        // concurrent consumes exactly one wins (rows==1) and the loser (rows==0) is rejected — the
        // in-memory consumedAt check above can race, this cannot.
        int claimed = magicLinks.markConsumed(link.getId(), LocalDateTime.now());
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This link has already been used.");
        }
        return Map.of("accessToken", accessTokens.sign(tenantId, email, System.currentTimeMillis()),
                "email", mask(email));
    }

    // ── listing ─────────────────────────────────────────────────────────────────

    /** Every open item assigned to the authenticated recipient across all capabilities, newest first. */
    @Transactional(readOnly = true)
    public Map<String, Object> listItems(String accessToken) {
        PortalAccessTokens.PortalRef ref = session(accessToken);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RecipientItem item : aggregate(ref.tenantId(), ref.email())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", item.type());
            row.put("token", item.token());
            row.put("title", item.title());
            row.put("status", item.status());
            row.put("sentAt", item.sentAt());
            row.put("expiresAt", item.expiresAt());
            out.add(row);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("email", mask(ref.email()));
        resp.put("items", out);
        return resp;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private String tenant(String tenantToken) {
        try {
            return tenantTokens.verify(tenantToken);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This portal link isn't valid.");
        }
    }

    private PortalAccessTokens.PortalRef session(String accessToken) {
        try {
            return accessTokens.verify(accessToken, System.currentTimeMillis());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verify your email to continue.");
        }
    }

    /** All open items for a recipient across every provider, newest first (null sentAt last). */
    private List<RecipientItem> aggregate(String tenantId, String email) {
        List<RecipientItem> all = new ArrayList<>();
        for (RecipientItemProvider p : providers) all.addAll(p.itemsFor(tenantId, email));
        all.sort(Comparator.comparing(RecipientItem::sentAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return all;
    }

    /** Whether the recipient has ANY open item (across providers) — the has-items gate for challenges. */
    private boolean hasItems(String tenantId, String email) {
        for (RecipientItemProvider p : providers) {
            if (!p.itemsFor(tenantId, email).isEmpty()) return true;
        }
        return false;
    }

    /** The first phone any provider holds for the recipient (SMS channel only). */
    private String firstPhone(String tenantId, String email) {
        for (RecipientItemProvider p : providers) {
            Optional<String> phone = p.phoneFor(tenantId, email);
            if (phone.isPresent() && !phone.get().isBlank()) return phone.get();
        }
        return null;
    }

    private static Map<String, Object> challengeResponse(PortalChannel channel, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("channel", channel.name());
        // Echo only the email (the caller supplied it — no leak). NEVER echo the phone: the recipient
        // didn't provide it, so a masked phone would reveal an on-file SMS contact for that email.
        m.put("sentTo", channel == PortalChannel.EMAIL ? mask(email) : null);
        return m;
    }

    /** Deliver an OTP off the request thread so has-items and no-items paths share the same latency,
     *  and a misbehaving/unconfigured sender can never fault the request (contains finding: SMS seam). */
    private void dispatchSend(PortalOtpSender sender, String tenantId, String contact, String code) {
        executor.execute(() -> {
            try {
                sender.send(tenantId, contact, code, null);
            } catch (RuntimeException e) {
                log.warn("Portal OTP delivery failed (channel {}): {}", sender.channel(), e.getMessage());
            }
        });
    }

    private void deliverMagicLink(String tenantId, String email, String url) {
        try {
            String subject = "Your sign-in link";
            String html = "<p>Use this secure link to see everything sent to you. It expires in 15 minutes and can be used once.</p>"
                    + "<p><a href=\"" + esc(url) + "\">Open my items</a></p>";
            String text = "Open your items (link expires in 15 minutes, single use):\n" + url + "\n";
            EmailRequest req = new EmailRequest(
                    null, email, null, null, null, subject, html, text,
                    null, null, null, "portal-magic", null, null, null);
            emails.sendRaw(tenantId, null, req);
        } catch (RuntimeException e) {
            log.warn("Portal magic-link email failed: {}", e.getMessage());
        }
    }

    private static String normalizeEmail(String email) {
        if (email == null) return null;
        String s = email.trim().toLowerCase();
        return s.isEmpty() ? null : s;
    }

    private static String randomSalt() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String code, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest((salt + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hash failure", e);
        }
    }

    static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hash failure", e);
        }
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
