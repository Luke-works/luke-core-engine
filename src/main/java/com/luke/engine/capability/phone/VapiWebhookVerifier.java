package com.luke.engine.capability.phone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies that an incoming Vapi webhook is authentic. Vapi sends the shared secret configured on
 * the assistant/phone-number ({@code server.secret}) back in the {@code X-Vapi-Secret} header; we
 * compare it, constant-time, against {@code luke.phone.vapi.webhook-secret}.
 *
 * <p>Default-lenient (matching the platform): if no secret is configured the webhook is allowed and
 * a warning is logged, so dev/qa work without setup. In production set the secret AND
 * {@code luke.phone.vapi.webhook-require-secret=true} so an unsigned/mismatched call is rejected
 * (the {@code prod} profile should pin this on). Constant-time compare mirrors {@code EmbedTokens}
 * and {@code InternalAuthFilter}.
 */
@Component
public class VapiWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(VapiWebhookVerifier.class);

    @Value("${luke.phone.vapi.webhook-secret:}")
    private String webhookSecret;

    @Value("${luke.phone.vapi.webhook-require-secret:false}")
    private boolean requireSecret;

    /** Whether the {@code X-Vapi-Secret} header authenticates this request. */
    public boolean verify(String presentedSecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            if (requireSecret) {
                log.warn("Vapi webhook rejected: luke.phone.vapi.webhook-secret is required but unset");
                return false;
            }
            log.warn("Vapi webhook accepted WITHOUT verification — set luke.phone.vapi.webhook-secret to secure it");
            return true;
        }
        if (presentedSecret == null) return false;
        return MessageDigest.isEqual(
                webhookSecret.getBytes(StandardCharsets.UTF_8),
                presentedSecret.getBytes(StandardCharsets.UTF_8));
    }
}
