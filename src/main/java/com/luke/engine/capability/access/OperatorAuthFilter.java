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
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Requires an operator credential (HTTP Basic) on the tenant-admin capability
 * routes — subscriptions and per-user grants under {@code /api/tenants/**}. These
 * are privileged, server-to-server endpoints: they decide who can use what, so
 * leaving them open lets anyone grant themselves capabilities. Only core-engine
 * (after it has authorized the caller as a tenant-admin) should reach them, and
 * it presents the same credential.
 *
 * <p>User-facing routes ({@code /api/my-capabilities}, {@code /api/my-subscriptions},
 * form routes) are unaffected — they authenticate with the gateway Bearer token
 * via {@link GatewayAuthFilter}.
 *
 * <p>Enforced only when {@code CAPABILITY_OPERATOR_USER} is configured; otherwise
 * the filter passes through (local dev / Postman), mirroring GatewayAuthFilter.
 */
@Configuration
public class OperatorAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(OperatorAuthFilter.class);

    @Bean
    public FilterRegistrationBean<Filter> operatorAuthFilterRegistration(
            @Value("${luke.auth.operator.user:}") String user,
            @Value("${luke.auth.operator.password:}") String password) {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new Impl(user, password));
        // /api/tenants/**        : all methods (privileged subscription/grant admin).
        // /api/users/**          : all methods — DELETE /api/users/{userId} purges a
        //                          user's grants platform-wide; it was previously covered
        //                          by NO auth filter (#50), so it's added here.
        // /api/capabilities(+/*)  : WRITES only — the GLOBAL catalog. GET stays public.
        //                           (Closes the backlog gap: catalog create/upsert/delete
        //                           previously had no auth filter at all.)
        reg.addUrlPatterns("/api/tenants/*", "/api/users/*", "/api/capabilities", "/api/capabilities/*");
        reg.setName("operatorAuthFilter");
        reg.setOrder(0); // before the gateway/capability filters
        return reg;
    }

    private static class Impl implements Filter {
        private final String expected; // "Basic base64(user:pass)" or null when unconfigured

        Impl(String user, String password) {
            if (user != null && !user.isBlank()) {
                this.expected = "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
                log.info("OperatorAuthFilter: enabled — /api/tenants/** and /api/capabilities writes require the operator credential");
            } else {
                this.expected = null;
                log.warn("OperatorAuthFilter: DISABLED — /api/tenants/** and /api/capabilities writes are unauthenticated; "
                        + "set CAPABILITY_OPERATOR_USER/CAPABILITY_OPERATOR_PASSWORD to enforce");
            }
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse res = (HttpServletResponse) response;

            // The GLOBAL capability catalog (/api/capabilities) is readable by anyone;
            // only WRITES (create/upsert/delete) need the operator credential. Tenant
            // admin routes (/api/tenants/**) require it on every method.
            String path = req.getRequestURI();
            String method = req.getMethod();
            boolean catalog = path.equals("/api/capabilities") || path.startsWith("/api/capabilities/");
            boolean write = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                    || "DELETE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);

            if (expected == null || "OPTIONS".equalsIgnoreCase(method) || (catalog && !write)) {
                chain.doFilter(request, response); // dev / preflight / public catalog read
                return;
            }

            String auth = req.getHeader("Authorization");
            if (auth == null || !constantTimeEquals(auth.trim(), expected)) {
                com.luke.engine.web.ApiError.write(res, HttpServletResponse.SC_UNAUTHORIZED,
                        "Unauthorized", "Operator credential required");
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean constantTimeEquals(String a, String b) {
            return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
        }
    }
}
