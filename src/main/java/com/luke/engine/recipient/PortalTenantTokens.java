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
 * Opaque, tamper-proof per-tenant handle for the recipient PORTAL URL ({@code /portal/{token}}).
 * Wraps just the {@code tenantId} — the portal is per-tenant (a recipient sees only that tenant's
 * forms), so the page must know its tenant up front without a raw id ever appearing in the URL or
 * being enumerable. Mirrors {@link EmbedTokens} minus the form code/version.
 *
 * <p>Shape: {@code <garbage>~<base64url(tenantId)>~<garbage>.<hmac>}. The secret lives only here.
 */
@Component
public class PortalTenantTokens {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final byte[] secret;

    public PortalTenantTokens(@Value("${luke.forms.portal-tenant-secret:dev-portal-tenant-secret-change-me}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Mint an opaque, signed portal handle for a tenant. */
    public String sign(String tenantId) {
        String payload = B64.encodeToString(tenantId.getBytes(StandardCharsets.UTF_8));
        String body = garbage() + "~" + payload + "~" + garbage();
        return body + "." + hmac(body);
    }

    /** Verify a portal handle and return its tenant id. Throws on tamper/format error. */
    public String verify(String token) {
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
        String tenantId = new String(B64D.decode(parts[1]), StandardCharsets.UTF_8);
        if (tenantId.isBlank()) throw new IllegalArgumentException("bad payload");
        return tenantId;
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
