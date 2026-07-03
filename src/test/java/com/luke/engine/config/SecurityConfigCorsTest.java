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
}
