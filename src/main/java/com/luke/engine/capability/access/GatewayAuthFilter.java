package com.luke.engine.capability.access;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * When the {@link GatewayTokenVerifier} is enabled, this filter requires a valid
 * gateway act-as token on the user-facing capability routes and replaces the
 * {@code X-User-Id} header with the token's verified {@code sub} — so any client
 * value is ignored and impersonation is impossible. If the token carries a
 * {@code tenant} claim, {@code X-Tenant-Id} is replaced too.
 *
 * <p>When the verifier is disabled (local dev), the filter passes through and
 * the existing header-based identity is used unchanged.
 *
 * <p>Scoped to the user-facing routes only; admin grant/subscription routes are
 * a separate (operator-auth) concern.
 */
@Configuration
public class GatewayAuthFilter {

    @Bean
    public FilterRegistrationBean<Filter> gatewayAuthFilterRegistration(GatewayTokenVerifier verifier) {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new Impl(verifier));
        reg.addUrlPatterns("/api/form-definitions/*", "/api/form-instances/*", "/api/emails/*",
                "/api/email-servers/*", "/api/email-verification/*", "/api/email-templates/*",
                // Signatures (merged from luke-signature-engine): same authed routes the
                // CapabilityAccessInterceptor guards — must inject the verified X-User-Id here
                // too. The public token-signing routes (/api/public/sign*) are NOT listed.
                "/api/signature-definitions/*", "/api/signature-instances/*", "/api/signatures/*",
                // PHONE (Vapi) — the CapabilityAccessInterceptor guards these, so the verified
                // X-User-Id must be injected here too or every phone route 401s under the gateway.
                "/api/phone-calls/*", "/api/phone-numbers/*", "/api/phone-settings/*",
                // WORKFLOW — definitions/versions lifecycle, catalog, and the integrations module.
                "/api/workflow/*",
                // EMAIL boxes — the authed send-from/receive-at address manager. Its Javadoc claims
                // "guarded by the EMAIL capability" but it was never wired here or in AccessWebConfig
                // (#20), so it was reachable with only a spoofable X-Tenant-Id. Also added to the EMAIL
                // CapabilityAccessInterceptor.
                "/api/email-boxes/*",
                // Authenticated minion proxy — runs tenant provider credentials server-side; expects
                // the gateway to authenticate the user (its Javadoc says so) but was never wired (#20).
                "/api/minions/*",
                "/api/my-capabilities",
                // Sibling of /api/my-capabilities — was missing here, so it returned any tenant's active
                // subscriptions for a header-supplied X-Tenant-Id (cross-tenant disclosure) (#20).
                "/api/my-subscriptions",
                "/api/access-requests/*", "/api/my-access-requests", "/api/org/access-requests/*");
        reg.setName("gatewayAuthFilter");
        reg.setOrder(1); // before the capability access interceptor
        return reg;
    }

    private static class Impl implements Filter {
        private final GatewayTokenVerifier verifier;

        Impl(GatewayTokenVerifier verifier) {
            this.verifier = verifier;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse res = (HttpServletResponse) response;

            if (!verifier.isEnabled() || "OPTIONS".equalsIgnoreCase(req.getMethod())) {
                chain.doFilter(request, response); // dev / preflight: keep header trust
                return;
            }

            String auth = req.getHeader("Authorization");
            String token = (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7))
                    ? auth.substring(7).trim() : null;
            GatewayTokenVerifier.Identity id = token == null ? null : verifier.verify(token);
            if (id == null) {
                com.luke.engine.web.ApiError.write(res, HttpServletResponse.SC_UNAUTHORIZED,
                        "Unauthorized", "Valid gateway token required");
                return;
            }

            chain.doFilter(new IdentityRequest(req, id.userId(), id.tenant()), response);
        }
    }

    /** Overrides X-User-Id (and X-Tenant-Id when the token asserts one) with verified values. */
    private static class IdentityRequest extends HttpServletRequestWrapper {
        private final String userId;
        private final String tenant;

        IdentityRequest(HttpServletRequest request, String userId, String tenant) {
            super(request);
            this.userId = userId;
            this.tenant = tenant;
        }

        @Override
        public String getHeader(String name) {
            if ("X-User-Id".equalsIgnoreCase(name)) return userId;
            if (tenant != null && "X-Tenant-Id".equalsIgnoreCase(name)) return tenant;
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("X-User-Id".equalsIgnoreCase(name)) return Collections.enumeration(java.util.List.of(userId));
            if (tenant != null && "X-Tenant-Id".equalsIgnoreCase(name)) return Collections.enumeration(java.util.List.of(tenant));
            return super.getHeaders(name);
        }
    }
}
