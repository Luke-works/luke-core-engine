package com.luke.engine.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Guard against shipping the dev-default cryptographic keys (#58): the secrets
 * master key ({@code luke.secrets.keys.dev}) and the embed-token HMAC secret
 * ({@code luke.embed.hmac-secret}) both fall back to a well-known
 * {@code …-change-me} value when their env var is unset — which would let anyone
 * who reads the (public) source forge embed tokens or decrypt stored tenant secrets.
 *
 * <p>Enforcement is fail-fast when EITHER the dedicated {@code prod} profile is active
 * ({@link StrictProfile}) OR the opt-in {@code luke.security.require-strong-keys=true}
 * flag is set. It is deliberately NOT keyed off the {@code postgres} profile like the
 * admin-password / H2 guards, because dev/qa run {@code postgres} without these
 * {@code sync:false} keys — a postgres-based fail-fast would crash them. PROD runs
 * {@code postgres,prod} with real keys set; until then it logs a loud warning.
 */
@Component
public class InsecureKeyGuard {

    private static final Logger log = LoggerFactory.getLogger(InsecureKeyGuard.class);
    static final String DEV_EMBED = "dev-embed-secret-change-me";
    static final String DEV_SECRETS = "dev-secrets-master-key-change-me";
    static final String DEV_RECIPIENT = "dev-recipient-secret-change-me";

    private final String embedSecret;
    private final String devSecretsKey;
    private final String recipientSecret;
    private final boolean requireStrong;

    @Autowired
    public InsecureKeyGuard(
            @Value("${luke.embed.hmac-secret:}") String embedSecret,
            @Value("${luke.secrets.keys.dev:}") String devSecretsKey,
            @Value("${luke.forms.recipient-hmac-secret:}") String recipientSecret,
            @Value("${luke.security.require-strong-keys:false}") boolean requireStrong,
            Environment environment) {
        // The prod profile forces strictness even if the opt-in flag is left unset.
        this(embedSecret, devSecretsKey, recipientSecret, requireStrong || StrictProfile.isActive(environment));
    }

    /** Test-friendly constructor: the effective strict flag is already resolved. */
    InsecureKeyGuard(String embedSecret, String devSecretsKey, String recipientSecret, boolean requireStrong) {
        this.embedSecret = embedSecret;
        this.devSecretsKey = devSecretsKey;
        this.recipientSecret = recipientSecret;
        this.requireStrong = requireStrong;
    }

    @PostConstruct
    void verify() {
        List<String> insecure = new ArrayList<>();
        if (DEV_EMBED.equals(embedSecret)) insecure.add("luke.embed.hmac-secret (LUKE_EMBED_HMAC_SECRET)");
        if (DEV_SECRETS.equals(devSecretsKey)) insecure.add("luke.secrets.keys.dev (LUKE_SECRETS_KEY_DEV)");
        // The recipient-fill HMAC secret signs post-OTP outbound-form access tokens; a known
        // value lets an attacker forge fill-session tokens and bypass OTP (#audit).
        if (DEV_RECIPIENT.equals(recipientSecret)) insecure.add("luke.forms.recipient-hmac-secret (FORMS_RECIPIENT_HMAC_SECRET)");
        if (insecure.isEmpty()) {
            return;
        }
        if (requireStrong) {
            throw new IllegalStateException(
                    "Refusing to start: insecure dev-default cryptographic key(s) in use: " + insecure
                    + ". Set strong random values before deploying (the 'prod' profile enforces this).");
        }
        log.warn("INSECURE dev-default key(s) in use: {} — acceptable for local dev only. Set real values; the "
                + "'prod' profile (or luke.security.require-strong-keys=true) then enforces this at startup.", insecure);
    }
}
