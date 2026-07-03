package com.luke.engine.workflow.integrations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies that an incoming Nango webhook is authentic. Nango signs each webhook with
 * {@code X-Nango-Signature} = {@code sha256(secretKey + rawBody)} (hex); we recompute it
 * over the exact received body and compare constant-time.
 *
 * <p>Default-lenient (matching the platform, mirroring {@code VapiWebhookVerifier}): with
 * no secret configured the webhook is allowed and a warning logged, so dev/qa work without
 * setup. In production set {@code luke.workflow.nango.webhook-require-signature=true} (the
 * {@code prod} profile should pin it) so an unsigned/mismatched call is rejected — fail-closed.
 */
@Component
public class NangoWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(NangoWebhookVerifier.class);

    @Value("${luke.workflow.nango.secret-key:}")
    private String secretKey;

    @Value("${luke.workflow.nango.webhook-require-signature:false}")
    private boolean requireSignature;

    /** Whether {@code X-Nango-Signature} authenticates this exact {@code rawBody}. */
    public boolean verify(String signature, String rawBody) {
        if (secretKey == null || secretKey.isBlank()) {
            if (requireSignature) {
                log.warn("Nango webhook rejected: secret key required but unset");
                return false;
            }
            log.warn("Nango webhook accepted WITHOUT verification — set luke.workflow.nango.secret-key to secure it");
            return true;
        }
        if (signature == null || rawBody == null) return false;
        String computed = sha256Hex(secretKey + rawBody);
        if (computed == null) return false;
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null; // SHA-256 is always present on a JRE
        }
    }
}
