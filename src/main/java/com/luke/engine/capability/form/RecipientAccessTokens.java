package com.luke.engine.capability.form;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Short-lived, HMAC-signed access tokens for the public outbound fill surface, minted only after
 * a recipient passes the OTP challenge. Mirrors {@link EmbedTokens} but binds to a single instance
 * token and carries an expiry, so a leaked access token stops working on its own. The fill/submit
 * endpoints require a valid, unexpired token whose instance matches the path.
 *
 * <p>Shape: {@code <garbage>~<base64url(instanceToken|expEpochMs)>~<garbage>.<hmac>}.
 */
@Component
public class RecipientAccessTokens {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final byte[] secret;
    private final long ttlMs;

    public RecipientAccessTokens(
            @Value("${luke.forms.recipient-hmac-secret:dev-recipient-secret-change-me}") String secret,
            @Value("${luke.forms.recipient-session-ttl-ms:1800000}") long ttlMs) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlMs = ttlMs;
    }

    /** Mint an access token for {@code instanceToken}, valid for the configured TTL (default 30 min). */
    public String sign(String instanceToken, long nowMs) {
        long exp = nowMs + ttlMs;
        String payload = B64.encodeToString((instanceToken + "|" + exp).getBytes(StandardCharsets.UTF_8));
        String body = garbage() + "~" + payload + "~" + garbage();
        return body + "." + hmac(body);
    }

    /** Verify a token and return the instance token it authorizes, or throw on tamper/expiry. */
    public String verify(String token, long nowMs) {
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
        if (f.length != 2 || f[0].isBlank()) throw new IllegalArgumentException("bad payload");
        long exp;
        try {
            exp = Long.parseLong(f[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad payload");
        }
        if (nowMs > exp) throw new IllegalArgumentException("expired");
        return f[0];
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
