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
 * Opaque, tamper-proof embed tokens. A token wraps the {@code tenantId} and form
 * {@code code} in random padding and signs the whole thing with an HMAC, so the
 * public embed surface (the only unauthenticated path in the system) can trust
 * which tenant/form a request is for without anyone being able to forge one for
 * a different tenant.
 *
 * <p>Shape: {@code <garbage>~<base64url(tenant|code)>~<garbage>.<hmac>} — random
 * garbage before and after the encoded ids, then a signature over the body.
 * The HMAC secret lives only in this service (minting and verifying are both
 * here), so there is no cross-service secret to share.
 */
@Component
public class EmbedTokens {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final byte[] secret;

    public EmbedTokens(@Value("${luke.embed.hmac-secret:dev-embed-secret-change-me}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** The tenant + form a verified token resolves to. */
    public record EmbedRef(String tenantId, String code) {}

    /** Mint an opaque, signed token for a tenant's form code. */
    public String sign(String tenantId, String code) {
        String payload = B64.encodeToString((tenantId + "|" + code).getBytes(StandardCharsets.UTF_8));
        String body = garbage(4 + RNG.nextInt(5)) + "~" + payload + "~" + garbage(4 + RNG.nextInt(5));
        return body + "." + hmac(body);
    }

    /** Verify a token and decode its tenant/code. Throws on tamper/format error. */
    public EmbedRef verify(String token) {
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
        String decoded = new String(B64D.decode(parts[1]), StandardCharsets.UTF_8);
        int bar = decoded.indexOf('|');
        if (bar <= 0 || bar == decoded.length() - 1) throw new IllegalArgumentException("bad payload");
        return new EmbedRef(decoded.substring(0, bar), decoded.substring(bar + 1));
    }

    private static String garbage(int n) {
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
