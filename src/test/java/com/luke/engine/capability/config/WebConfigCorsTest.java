package com.luke.engine.capability.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

/**
 * The MVC-level CORS mapping runs on dispatch, AFTER SecurityConfig's CorsFilter. Both layers
 * must agree on the public embed/sign surface or cross-origin embeds still 403. This asserts the
 * WebConfig layer opens /api/public/** to any origin (no credentials), ordered before /api/**.
 */
class WebConfigCorsTest {

    @SuppressWarnings("unchecked")
    private Map<String, CorsConfiguration> mappings() {
        WebConfig wc = new WebConfig("https://app.example.com");
        CorsRegistry registry = new CorsRegistry();
        wc.addCorsMappings(registry);
        // getCorsConfigurations() is protected on CorsRegistry.
        return (Map<String, CorsConfiguration>) ReflectionTestUtils.invokeMethod(registry, "getCorsConfigurations");
    }

    @Test
    void publicSurfaceAllowsAnyOriginWithoutCredentials() {
        CorsConfiguration pub = mappings().get("/api/public/**");
        assertNotNull(pub, "public embed surface must have its own MVC CORS mapping");
        assertTrue(pub.getAllowedOriginPatterns().contains("*"), "public surface must allow any origin");
        assertFalse(Boolean.TRUE.equals(pub.getAllowCredentials()), "public surface must NOT send credentials");
    }

    @Test
    void catchAllApiMappingStaysRestrictedToTheAllowlist() {
        CorsConfiguration api = mappings().get("/api/**");
        assertNotNull(api);
        assertTrue(api.getAllowedOriginPatterns().contains("https://app.example.com"));
        assertFalse(api.getAllowedOriginPatterns().contains("*"), "first-party API must stay on the allowlist");
    }

    @Test
    void publicMappingIsOrderedBeforeCatchAll() {
        List<String> patterns = mappings().keySet().stream().toList();
        assertTrue(patterns.indexOf("/api/public/**") < patterns.indexOf("/api/**"),
                "public mapping must be registered before /api/** to win the path match");
    }
}
