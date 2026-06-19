package com.luke.engine.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Guard against shipping the dev-default cryptographic keys (#58): the secrets
 * master key ({@code luke.secrets.keys.dev}) and the embed-token HMAC secret
 * ({@code luke.embed.hmac-secret}) both fall back to a well-known
 * {@code …-change-me} value when their env var is unset — which would let anyone
 * who reads the (public) source forge embed tokens or decrypt stored tenant secrets.
 *
 * <p>Enforcement is OPT-IN via {@code luke.security.require-strong-keys=true}
 * (default false). It is NOT keyed off the prod profile like the admin-password /
 * H2 guards because dev/qa does not yet set these keys post-merge — a
 * profile-based fail-fast would crash that environment on deploy. PROD must set
 * real keys AND set this flag; until then it logs a loud warning.
 */
@Component
public class InsecureKeyGuard {

    private static final Logger log = LoggerFactory.getLogger(InsecureKeyGuard.class);
    static final String DEV_EMBED = "dev-embed-secret-change-me";
    static final String DEV_SECRETS = "dev-secrets-master-key-change-me";

    private final String embedSecret;
    private final String devSecretsKey;
    private final boolean requireStrong;

    public InsecureKeyGuard(
            @Value("${luke.embed.hmac-secret:}") String embedSecret,
            @Value("${luke.secrets.keys.dev:}") String devSecretsKey,
            @Value("${luke.security.require-strong-keys:false}") boolean requireStrong) {
        this.embedSecret = embedSecret;
        this.devSecretsKey = devSecretsKey;
        this.requireStrong = requireStrong;
    }

    @PostConstruct
    void verify() {
        List<String> insecure = new ArrayList<>();
        if (DEV_EMBED.equals(embedSecret)) insecure.add("luke.embed.hmac-secret (LUKE_EMBED_HMAC_SECRET)");
        if (DEV_SECRETS.equals(devSecretsKey)) insecure.add("luke.secrets.keys.dev (LUKE_SECRETS_KEY_DEV)");
        if (insecure.isEmpty()) {
            return;
        }
        if (requireStrong) {
            throw new IllegalStateException(
                    "Refusing to start: insecure dev-default cryptographic key(s) in use: " + insecure
                    + ". Set strong random values before deploying.");
        }
        log.warn("INSECURE dev-default key(s) in use: {} — acceptable for local dev only. Set real values and "
                + "luke.security.require-strong-keys=true in production.", insecure);
    }
}
