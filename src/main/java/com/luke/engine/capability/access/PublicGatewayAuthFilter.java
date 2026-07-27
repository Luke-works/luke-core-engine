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
 * Locks the PUBLIC surface ({@code /api/public/**}, {@code /embed/**}, {@code /embed-assets/**}) to the
 * gateway only. All legitimate embed / minion / public-document traffic reaches core through the
 * auth-engine gateway, which stamps a shared secret as {@code X-Gateway-Auth} (and strips any
 * client-supplied one). When the secret is configured, this filter requires that header on those paths,
 * so a request hitting core DIRECTLY — including the raw {@code *.onrender.com} URL that a Cloudflare
 * edge rule can't cover — is rejected. That closes the direct-to-core "back door" through which an
 * attacker could otherwise forge {@code X-Real-Client-IP} and bypass the public per-IP rate limits.
 *
 * <p>DEFAULT-OPEN (the deliberate inverse of {@link InternalAuthFilter}, which fails closed): the public
 * surface must keep serving with zero config, so an UNSET secret means this filter is a pass-through and
 * dev/qa are unaffected. It only enforces once {@code GATEWAY_VOUCH_SECRET} is set — set it on the
 * gateway FIRST (so it starts stamping the header), verify embed still works, then set the SAME value
 * here to arm the lock.
 */
@Configuration
public class PublicGatewayAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(PublicGatewayAuthFilter.class);

    @Bean
    public FilterRegistrationBean<Filter> publicGatewayAuthFilterRegistration(
            @Value("${GATEWAY_VOUCH_SECRET:}") String secret) {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new Impl(secret));
        reg.addUrlPatterns("/api/public/*", "/embed/*", "/embed-assets/*");
        reg.setName("publicGatewayAuthFilter");
        reg.setOrder(1); // early, before the per-controller rate limiters run
        return reg;
    }

    static class Impl implements Filter { // package-private for direct unit testing
        private final String expected; // the shared secret, or null when unconfigured (open)

        Impl(String secret) {
            if (secret != null && !secret.isBlank()) {
                this.expected = secret;
                log.info("PublicGatewayAuthFilter: ENABLED — public/embed paths require a valid "
                        + "X-Gateway-Auth (gateway-only).");
            } else {
                this.expected = null;
                log.info("PublicGatewayAuthFilter: disabled (GATEWAY_VOUCH_SECRET unset) — public/embed "
                        + "paths are open. Set the secret on the gateway then here to lock the back door.");
            }
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse res = (HttpServletResponse) response;

            // Preflight and the open (unconfigured) default both pass straight through.
            if (expected == null || "OPTIONS".equalsIgnoreCase(req.getMethod())) {
                chain.doFilter(request, response);
                return;
            }

            String presented = req.getHeader("X-Gateway-Auth");
            if (presented == null || !constantTimeEquals(presented, expected)) {
                // A direct-to-core hit (no valid gateway vouch). 404 — don't advertise that a gated
                // surface exists here at all; a browser only ever reaches these paths via the gateway.
                com.luke.engine.web.ApiError.write(res, HttpServletResponse.SC_NOT_FOUND,
                        "Not Found", "Not found");
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean constantTimeEquals(String a, String b) {
            return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
        }
    }
}
