package com.luke.engine.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Verifies the "act-as-user" tokens minted by {@code luke-auth-engine} (the
 * consumer gateway) and extracts the engine userId they assert.
 *
 * <p>This is the engine side of the gateway trust relationship. The gateway
 * signs a short-lived JWT with its private key; here we verify the signature
 * against the gateway's published JWKS and check issuer/audience/expiry. The
 * {@code sub} of a valid token is the userId the request should act as — the
 * {@link RestApiAuthFilter} then resolves that user's groups/tenants and calls
 * {@code setAuthentication} exactly as the Basic-auth path does.
 *
 * <p>Disabled unless {@code luke.auth.gateway.enabled=true} and a JWKS url is
 * configured; when disabled, {@link #authenticate} always returns null so the
 * engine behaves exactly as before (Basic-only). Fails closed on any error.
 */
@Component
public class GatewayJwtAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(GatewayJwtAuthenticator.class);

    @Value("${luke.auth.gateway.enabled:false}")
    private boolean enabled;

    @Value("${luke.auth.gateway.jwks-url:}")
    private String jwksUrl;

    @Value("${luke.auth.gateway.issuer:luke-auth-engine}")
    private String expectedIssuer;

    @Value("${luke.auth.gateway.audience:luke-core-engine}")
    private String expectedAudience;

    private NimbusJwtDecoder decoder;

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("GatewayJwtAuthenticator: disabled (Basic auth only). "
                    + "Set luke.auth.gateway.enabled=true to accept consumer-gateway Bearer tokens.");
            return;
        }
        if (!StringUtils.hasText(jwksUrl)) {
            log.error("GatewayJwtAuthenticator: enabled but luke.auth.gateway.jwks-url is missing — "
                    + "gateway Bearer tokens will be REJECTED.");
            return;
        }
        this.decoder = NimbusJwtDecoder.withJwkSetUri(jwksUrl).build();
        log.info("GatewayJwtAuthenticator: enabled — verifying gateway tokens via JWKS {} (iss={}, aud={})",
                jwksUrl, expectedIssuer, expectedAudience);
    }

    /** True when the engine is configured to accept gateway Bearer tokens. */
    public boolean isEnabled() {
        return enabled && decoder != null;
    }

    /**
     * Verify a gateway act-as-user token and return the asserted engine userId
     * (its {@code sub}), or {@code null} if the token is missing, invalid,
     * expired, or fails issuer/audience checks.
     */
    public String authenticate(String rawToken) {
        if (decoder == null || !StringUtils.hasText(rawToken)) {
            return null;
        }
        try {
            Jwt jwt = decoder.decode(rawToken);

            if (!expectedIssuer.equals(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())) {
                log.warn("Gateway token rejected: issuer mismatch (got {})", jwt.getIssuer());
                return null;
            }
            List<String> aud = jwt.getAudience();
            if (aud == null || !aud.contains(expectedAudience)) {
                log.warn("Gateway token rejected: audience mismatch (got {})", aud);
                return null;
            }
            String sub = jwt.getSubject();
            if (!StringUtils.hasText(sub)) {
                log.warn("Gateway token rejected: missing subject");
                return null;
            }
            return sub;
        } catch (JwtException e) {
            log.debug("Gateway token rejected: {}", e.getMessage());
            return null;
        }
    }
}
