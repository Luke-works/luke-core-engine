package com.luke.engine.recipient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Short-lived, HMAC-signed session token for the recipient PORTAL, minted only after a recipient
 * passes an OTP or magic-link challenge. Unlike {@link RecipientAccessTokens} (which binds a single
 * instance token), this binds a {@code (tenantId, email)} pair, so it authorises EVERY open instance
 * whose recipient email + tenant match — that is what makes "authenticate once, see all my forms"
 * work. Carries an expiry so a leaked session dies on its own.
 *
 * <p>Shape: {@code <garbage>~<base64url(tenantId|base64url(email)|expEpochMs)>~<garbage>.<hmac>}.
 * The email is itself base64url-encoded inside the payload so it can never collide with the {@code |}
 * delimiter. The secret lives only here (mint + verify both local).
 */
@Component
public class PortalAccessTokens {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final byte[] secret;
    private final long ttlMs;

    public PortalAccessTokens(
            @Value("${luke.forms.portal-hmac-secret:dev-portal-secret-change-me}") String secret,
            @Value("${luke.forms.portal-session-ttl-ms:1800000}") long ttlMs) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlMs = ttlMs;
    }

    /** The (tenant, email) pair a verified portal session authorises. */
    public record PortalRef(String tenantId, String email) {}

    /** Mint a portal session for {@code (tenantId, email)}, valid for the configured TTL (default 30 min). */
    public String sign(String tenantId, String email, long nowMs) {
        long exp = nowMs + ttlMs;
        String emailB64 = B64.encodeToString(email.getBytes(StandardCharsets.UTF_8));
        String payload = B64.encodeToString((tenantId + "|" + emailB64 + "|" + exp).getBytes(StandardCharsets.UTF_8));
        String body = garbage() + "~" + payload + "~" + garbage();
        return body + "." + hmac(body);
    }

    /** Verify a session token and return its (tenant, email), or throw on tamper/expiry. */
    public PortalRef verify(String token, long nowMs) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("missing token");
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) throw new IllegalArgumentException("bad token");
        String body = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!MessageDigest.isEqual(hmac(body).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("bad signature");
        }
        String[] parts = body.split("~");
        if (parts.length != 3) throw new IllegalArgumentException("bad token body");
        String[] f = new String(B64D.decode(parts[1]), StandardCharsets.UTF_8).split("\\|", -1);
        if (f.length != 3 || f[0].isBlank() || f[1].isBlank()) throw new IllegalArgumentException("bad payload");
        long exp;
        try {
            exp = Long.parseLong(f[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad payload");
        }
        if (nowMs > exp) throw new IllegalArgumentException("expired");
        String email = new String(B64D.decode(f[1]), StandardCharsets.UTF_8);
        if (email.isBlank()) throw new IllegalArgumentException("bad payload");
        return new PortalRef(f[0], email);
    }

    private static String garbage() {
        int n = 4 + RNG.nextInt(5);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(ALNUM.charAt(RNG.nextInt(ALNUM.length())));
        return sb.toString();
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }
}
