package com.luke.engine.capability.access;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Guards the server-to-server routes under {@code /api/internal/**} (e.g. the
 * process-triggered email endpoint) with the shared internal secret. Callers
 * present it as {@code X-Internal-Key}; it must match {@code luke.internal.shared-secret}
 * — the SAME secret core-engine uses on its own internal hops (see ProcessStarter).
 *
 * <p>Fails CLOSED: when the secret is not configured the filter REFUSES every
 * {@code /api/internal/**} request with 503 — these routes expose decrypted
 * tenant secrets, so an unconfigured deployment must not serve them. For local
 * dev/Postman, set {@code luke.internal.allow-insecure=true} to explicitly opt
 * out of enforcement (never in production).
 */
@Configuration
public class InternalAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthFilter.class);

    @Bean
    public FilterRegistrationBean<Filter> internalAuthFilterRegistration(
            @Value("${luke.internal.shared-secret:}") String sharedSecret,
            @Value("${luke.internal.allow-insecure:false}") boolean allowInsecure) {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new Impl(sharedSecret, allowInsecure));
        reg.addUrlPatterns("/api/internal/*");
        reg.setName("internalAuthFilter");
        reg.setOrder(0); // before the gateway/capability filters; these routes skip both
        return reg;
    }

    private static class Impl implements Filter {
        private final String expected;       // the shared secret, or null when unconfigured
        private final boolean allowInsecure; // explicit local-dev opt-out of enforcement

        Impl(String sharedSecret, boolean allowInsecure) {
            this.allowInsecure = allowInsecure;
            if (sharedSecret != null && !sharedSecret.isBlank()) {
                this.expected = sharedSecret;
                log.info("InternalAuthFilter: enabled — /api/internal/** requires X-Internal-Key");
            } else {
                this.expected = null;
                if (allowInsecure) {
                    log.warn("InternalAuthFilter: shared secret UNSET and luke.internal.allow-insecure=true — "
                            + "/api/internal/** is OPEN. For local dev only; NEVER use in production.");
                } else {
                    log.error("InternalAuthFilter: shared secret UNSET — /api/internal/** will be REFUSED (503). "
                            + "Set LUKE_INTERNAL_SHARED_SECRET (or luke.internal.allow-insecure=true for local dev).");
                }
            }
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse res = (HttpServletResponse) response;

            if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
                chain.doFilter(request, response); // CORS preflight
                return;
            }

            // Fail closed: an unconfigured secret means these secret-bearing routes
            // are refused outright, unless the operator explicitly opted out for dev.
            if (expected == null) {
                if (allowInsecure) {
                    chain.doFilter(request, response);
                    return;
                }
                com.luke.engine.web.ApiError.write(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Service Unavailable", "Internal auth is not configured; set LUKE_INTERNAL_SHARED_SECRET");
                return;
            }

            String key = req.getHeader("X-Internal-Key");
            if (key == null || !constantTimeEquals(key, expected)) {
                com.luke.engine.web.ApiError.write(res, HttpServletResponse.SC_UNAUTHORIZED,
                        "Unauthorized", "Internal shared secret required");
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean constantTimeEquals(String a, String b) {
            return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
        }
    }
}
