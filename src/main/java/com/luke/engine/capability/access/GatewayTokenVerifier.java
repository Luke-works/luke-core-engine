package com.luke.engine.capability.access;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Verifies the gateway act-as token (minted by luke-auth-engine) against its
 * published JWKS, so the user identity comes from a signed {@code sub} claim
 * rather than a spoofable {@code X-User-Id} header.
 *
 * <p>Config-driven, mirroring core-engine: if {@code luke.auth.gateway.jwks-url}
 * is unset the verifier is <b>disabled</b> and the service keeps trusting the
 * headers (fine for local dev / the Postman flow). Set it in shared
 * environments to require verified tokens.
 */
@Component
public class GatewayTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GatewayTokenVerifier.class);

    @Value("${luke.auth.gateway.jwks-url:}")
    private String jwksUrl;

    @Value("${luke.auth.gateway.issuer:luke-auth-engine}")
    private String expectedIssuer;

    @Value("${luke.auth.gateway.audience:luke-core-engine}")
    private String expectedAudience;

    private NimbusJwtDecoder decoder;

    /** Verified identity asserted by a valid token. {@code tenant} may be null. */
    public record Identity(String userId, String tenant) {}

    @PostConstruct
    void init() {
        if (!StringUtils.hasText(jwksUrl)) {
            log.info("GatewayTokenVerifier: disabled (no jwks-url) — trusting X-User-Id/X-Tenant-Id headers. "
                    + "Set luke.auth.gateway.jwks-url to require verified gateway tokens.");
            return;
        }
        this.decoder = NimbusJwtDecoder.withJwkSetUri(jwksUrl).build();
        log.info("GatewayTokenVerifier: enabled — verifying gateway tokens via JWKS {} (iss={}, aud={})",
                jwksUrl, expectedIssuer, expectedAudience);
    }

    public boolean isEnabled() {
        return decoder != null;
    }

    /** Verify the raw token; returns the asserted identity, or null if invalid. */
    public Identity verify(String rawToken) {
        if (decoder == null || !StringUtils.hasText(rawToken)) {
            return null;
        }
        try {
            Jwt jwt = decoder.decode(rawToken);
            // 'iss' is a service name, not a URL — read it as a plain string claim.
            if (!expectedIssuer.equals(jwt.getClaimAsString("iss"))) {
                return null;
            }
            List<String> aud = jwt.getAudience();
            if (aud == null || !aud.contains(expectedAudience)) {
                return null;
            }
            String sub = jwt.getSubject();
            if (!StringUtils.hasText(sub)) {
                return null;
            }
            return new Identity(sub, jwt.getClaimAsString("tenant"));
        } catch (Exception e) {
            log.debug("Gateway token rejected: {}", e.getMessage());
            return null;
        }
    }
}
