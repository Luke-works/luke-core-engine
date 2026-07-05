package com.luke.engine.capability.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the capability engine. In normal operation the UI reaches these
 * endpoints through luke-core-engine's CapabilitiesProxyController (same
 * origin), but allowing direct browser calls keeps local dev simple.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public WebConfig(@Value("${luke.cors.allowed-origins:http://localhost:*}") String origins) {
        this.allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Public embed/sign surface is unauthenticated (token/HMAC-gated, no cookies) and
        // designed for arbitrary third-party sites embedding a form, so it must accept any
        // origin — WITHOUT credentials. Must mirror SecurityConfig's CorsFilter policy: the
        // filter gates the request first, but this handler-level mapping runs again on
        // dispatch, so both layers have to agree or cross-origin embeds still 403.
        // Registered before "/api/**" so it wins the path match for public routes.
        registry.addMapping("/api/public/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept", "X-Tenant-Id")
                .allowCredentials(false);

        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*");
    }
}
