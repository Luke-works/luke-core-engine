package com.luke.engine.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Closes the unauthenticated {@code /api/**} endpoints that previously had NO
 * authentication at all, by enforcing the same Basic + gateway-Bearer scheme and
 * tenant-membership scoping that {@link RestApiAuthFilter} applies to
 * {@code /engine-rest/*}.
 *
 * <p>Background: the engine's systemic auth (RestApiAuthFilter / TenantFilter /
 * DeploymentTenantFilter) is registered only for {@code /engine-rest/*}. Each
 * {@code /api/**} controller therefore had to authenticate itself, and three did
 * not — leaving them open to anyone who could reach the service:
 * <ul>
 *   <li>{@code /api/form-inbox/**} — listed/completed any tenant's user tasks
 *       given only an {@code X-Tenant-Id} header (GHSA-69jj-m9hc-jv5q).</li>
 *   <li>{@code /api/process-trace/**} — read any process instance's trace, and
 *       skipped the tenant check entirely for null-tenant instances
 *       (GHSA-4vg7-j7qc-8f77).</li>
 *   <li>{@code /api/topics/**} — a fully unauthenticated, cross-tenant CRUD
 *       registry (GHSA-4gpc-qxwf-5fm9).</li>
 * </ul>
 *
 * <p>This filter is deliberately scoped to exactly those three endpoint groups
 * (the ones with no other line of defence). The remaining {@code /api/**} paths
 * are intentionally left untouched here because they already enforce auth in
 * their own controllers (e.g. {@code /api/org/**}, {@code /api/me/**},
 * {@code /api/admin/**}), are guarded by a shared secret
 * ({@code /api/internal/**}), are public by design ({@code /api/public/**}), or
 * delegate enforcement to capability-engine (the proxy paths). Consolidating
 * <em>all</em> of {@code /api/**} under one default-deny filter is the
 * recommended follow-up once the gateway-Bearer rollout state is confirmed in
 * each environment (see PR notes).
 *
 * <p>Scope rules mirror {@link RestApiAuthFilter} exactly: a specific
 * {@code X-Tenant-Id} scopes the request to that tenant and a non-privileged
 * caller that is not a member gets 403; no selection keeps the caller's full
 * memberships. Writes to the global {@code /api/topics} registry additionally
 * require an operator (parent-cluster member or {@code camunda-admin}).
 */
@Configuration
public class ApiAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthFilter.class);

    private static final String CAMUNDA_ADMIN_GROUP = "camunda-admin";

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    @Bean
    public FilterRegistrationBean<Filter> apiAuthFilterRegistration(IdentityService identityService,
                                                                    GatewayJwtAuthenticator gatewayAuth) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiAuthServletFilter(identityService, gatewayAuth, parentClusterId));
        // "/foo/*" matches both "/foo" and "/foo/bar", so the base paths are covered too.
        registration.addUrlPatterns("/api/form-inbox/*", "/api/process-trace/*", "/api/topics/*");
        registration.setName("apiAuthFilter");
        registration.setOrder(1);
        log.info("ApiAuthFilter registered — Basic + {} gateway-Bearer; enforcing auth+tenant on "
                        + "/api/form-inbox, /api/process-trace, /api/topics (writes to /api/topics require operator)",
                gatewayAuth.isEnabled() ? "enabled" : "disabled");
        return registration;
    }

    private static class ApiAuthServletFilter implements Filter {

        private final IdentityService identityService;
        private final GatewayJwtAuthenticator gatewayAuth;
        private final String parentClusterId;

        ApiAuthServletFilter(IdentityService identityService, GatewayJwtAuthenticator gatewayAuth,
                             String parentClusterId) {
            this.identityService = identityService;
            this.gatewayAuth = gatewayAuth;
            this.parentClusterId = parentClusterId;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {

            HttpServletRequest httpReq = (HttpServletRequest) request;
            HttpServletResponse httpResp = (HttpServletResponse) response;

            // Let CORS preflight through (mirrors RestApiAuthFilter).
            if ("OPTIONS".equalsIgnoreCase(httpReq.getMethod())) {
                chain.doFilter(request, response);
                return;
            }

            String authHeader = httpReq.getHeader("Authorization");
            if (authHeader == null) {
                sendUnauthorized(httpResp);
                return;
            }

            try {
                String lower = authHeader.toLowerCase();
                String username;
                if (lower.startsWith("bearer ")) {
                    username = authenticateBearer(authHeader, httpResp);
                } else if (lower.startsWith("basic ")) {
                    username = authenticateBasic(authHeader, httpResp);
                } else {
                    sendUnauthorized(httpResp);
                    return;
                }
                if (username == null) {
                    return; // an error response was already written
                }

                List<String> userTenants = identityService.createTenantQuery()
                        .userMember(username)
                        .list()
                        .stream()
                        .map(Tenant::getId)
                        .toList();
                List<String> groupIds = identityService.createGroupQuery()
                        .groupMember(username)
                        .list()
                        .stream()
                        .map(Group::getId)
                        .toList();

                boolean privileged = userTenants.contains(parentClusterId)
                        || groupIds.contains(CAMUNDA_ADMIN_GROUP);

                // The topic registry is global (not tenant-scoped); only operators may mutate it.
                String path = httpReq.getRequestURI();
                if (path != null && path.startsWith("/api/topics") && !isReadMethod(httpReq.getMethod())
                        && !privileged) {
                    log.warn("User '{}' attempted to modify the topic registry without operator rights", username);
                    sendForbiddenMessage(httpResp, "Operator rights required to modify the topic registry");
                    return;
                }

                List<String> scopeTenants = resolveScope(httpReq, username, userTenants, groupIds, httpResp);
                if (scopeTenants == null) {
                    return; // a 403 was already written
                }

                identityService.setAuthentication(username, groupIds, scopeTenants);
                try {
                    chain.doFilter(request, response);
                } finally {
                    identityService.clearAuthentication();
                }
            } catch (IllegalArgumentException e) {
                log.debug("Malformed Authorization header");
                sendUnauthorized(httpResp);
            }
        }

        private boolean isReadMethod(String method) {
            return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        }

        /** Validate HTTP Basic credentials; returns the username or null (response written). */
        private String authenticateBasic(String authHeader, HttpServletResponse httpResp) throws IOException {
            String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
            int colonIndex = decoded.indexOf(':');
            if (colonIndex < 0) {
                sendUnauthorized(httpResp);
                return null;
            }
            String username = decoded.substring(0, colonIndex);
            String password = decoded.substring(colonIndex + 1);
            if (!identityService.checkPassword(username, password)) {
                log.warn("Authentication failed for user: {}", username);
                sendUnauthorized(httpResp);
                return null;
            }
            return username;
        }

        /**
         * Validate a gateway act-as-user Bearer token; returns the engine userId
         * or null (response written). Mirrors {@link RestApiAuthFilter}.
         */
        private String authenticateBearer(String authHeader, HttpServletResponse httpResp) throws IOException {
            String userId = gatewayAuth.authenticate(authHeader.substring(7).trim());
            if (userId == null) {
                sendUnauthorized(httpResp);
                return null;
            }
            if (identityService.createUserQuery().userId(userId).count() == 0) {
                log.warn("Gateway-authenticated user '{}' is not provisioned in the engine", userId);
                sendNotProvisioned(httpResp, userId);
                return null;
            }
            return userId;
        }

        /**
         * Decide the authenticated tenant ids for this request.
         * Returns null (and writes 403) if the user may not act as the requested tenant.
         */
        private List<String> resolveScope(HttpServletRequest httpReq, String username,
                                          List<String> userTenants, List<String> groupIds,
                                          HttpServletResponse httpResp) throws IOException {
            String headerTenant = httpReq.getHeader("X-Tenant-Id");
            if (headerTenant != null) {
                headerTenant = headerTenant.trim();
            }
            boolean noSelection = headerTenant == null || headerTenant.isBlank()
                    || "null".equalsIgnoreCase(headerTenant);

            if (noSelection || headerTenant.equals(parentClusterId)) {
                return userTenants;
            }

            boolean privileged = userTenants.contains(parentClusterId) || groupIds.contains(CAMUNDA_ADMIN_GROUP);
            if (!privileged && !userTenants.contains(headerTenant)) {
                log.warn("User '{}' attempted to act as tenant '{}' without membership", username, headerTenant);
                sendForbidden(httpResp, headerTenant);
                return null;
            }
            return List.of(headerTenant);
        }

        private void sendUnauthorized(HttpServletResponse response) throws IOException {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Luke Core Engine\"");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Valid credentials required\"}");
        }

        private void sendForbidden(HttpServletResponse response, String tenantId) throws IOException {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Not a member of tenant '" + tenantId + "'\"}");
        }

        private void sendForbiddenMessage(HttpServletResponse response, String message) throws IOException {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"" + message + "\"}");
        }

        private void sendNotProvisioned(HttpServletResponse response, String userId) throws IOException {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"User '" + userId
                    + "' is authenticated but not yet onboarded to the engine\"}");
        }
    }
}
