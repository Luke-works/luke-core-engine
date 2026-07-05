package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** #33: CORS must not allow "*" headers alongside credentials. */
class SecurityConfigCorsTest {

    @Test
    void allowedHeadersIsAnExplicitAllowlistNotWildcard() {
        SecurityConfig sc = new SecurityConfig();
        ReflectionTestUtils.setField(sc, "allowedOrigins", "https://app.example.com");

        UrlBasedCorsConfigurationSource src =
                (UrlBasedCorsConfigurationSource) sc.corsConfigurationSource();
        CorsConfiguration cfg = src.getCorsConfigurations().get("/**");

        assertNotNull(cfg);
        assertNotNull(cfg.getAllowedHeaders());
        assertFalse(cfg.getAllowedHeaders().contains("*"), "must not allow all headers with credentials");
        assertTrue(cfg.getAllowedHeaders().contains("Authorization"));
        assertTrue(cfg.getAllowedHeaders().contains("X-Tenant-Id"));
        assertTrue(Boolean.TRUE.equals(cfg.getAllowCredentials()));
    }

    /**
     * The public embed/sign surface must accept any origin (third-party sites embedding a
     * form) but WITHOUT credentials — otherwise the first-party allowlist would 403 legit
     * cross-origin embed submissions. Public route must be registered before "/**".
     */
    @Test
    void publicSurfaceAllowsAnyOriginWithoutCredentials() {
        SecurityConfig sc = new SecurityConfig();
        ReflectionTestUtils.setField(sc, "allowedOrigins", "https://app.example.com");

        UrlBasedCorsConfigurationSource src =
                (UrlBasedCorsConfigurationSource) sc.corsConfigurationSource();
        CorsConfiguration pub = src.getCorsConfigurations().get("/api/public/**");

        assertNotNull(pub, "public embed surface must have its own CORS policy");
        assertNotNull(pub.getAllowedOriginPatterns());
        assertTrue(pub.getAllowedOriginPatterns().contains("*"), "public surface must allow any origin");
        assertFalse(Boolean.TRUE.equals(pub.getAllowCredentials()), "public surface must NOT send credentials");
        // Identity/trust headers stay server-to-server only, never reachable from a browser.
        assertFalse(pub.getAllowedHeaders().contains("Authorization"));

        // "/api/public/**" must be ordered before "/**" so it wins the path match.
        var patterns = src.getCorsConfigurations().keySet().stream().toList();
        assertTrue(patterns.indexOf("/api/public/**") < patterns.indexOf("/**"),
                "public route must be registered before the catch-all");
    }
}
