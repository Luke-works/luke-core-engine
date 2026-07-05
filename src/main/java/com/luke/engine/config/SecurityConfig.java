package com.luke.engine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Comma-separated origin patterns (e.g. "https://*.onrender.com,https://app.example.com").
     * Local default allows any localhost port; override via ALLOWED_ORIGINS env var in prod.
     */
    @Value("${luke.cors.allowed-origins:http://localhost:*}")
    private String allowedOrigins;

    /**
     * Spring Security permits everything — actual REST API auth is handled by
     * {@link RestApiAuthFilter} as a servlet filter.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // Explicit allowlist, NOT "*": with allowCredentials(true) a wildcard header
        // policy needlessly widens what a (possibly shared-host) origin can probe. Only
        // the headers browser clients actually send. Identity/trust headers (X-User-Id,
        // X-Internal-Key) are intentionally excluded — server-to-server only. (#33)
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Tenant-Id"));
        config.setAllowCredentials(true);
        // NOTE: keep ALLOWED_ORIGINS to exact hosts in prod — do NOT use a shared
        // wildcard like https://*.onrender.com (any onrender app's origin would match).
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Public embed/sign/documents surface (/api/public/**) is unauthenticated and
        // guarded by signed tokens/HMAC + rate limits + honeypot — NOT by cookies. It is
        // designed to be called from arbitrary third-party sites embedding a form, so CORS
        // must allow any origin. CORS is not an authz control here; opening it adds no
        // exposure because no credentials ride these requests. Kept WITHOUT credentials and
        // without identity/trust headers so it can never be widened into a credentialed hole.
        // Registered before "/**" so it wins the path match for public routes.
        CorsConfiguration publicConfig = new CorsConfiguration();
        publicConfig.setAllowedOriginPatterns(List.of("*"));
        publicConfig.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        publicConfig.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Tenant-Id"));
        publicConfig.setAllowCredentials(false);
        publicConfig.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/public/**", publicConfig);

        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
