package com.luke.engine.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.cibseven.bpm.engine.IdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.Tenant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Servlet filter that enforces HTTP Basic authentication on /engine-rest/*
 * by validating credentials against CIBSeven's IdentityService, and sets the
 * engine's authenticated tenant scope from the {@code X-Tenant-Id} header.
 *
 * <p>The authenticated tenant ids drive CIBSeven's native tenant checks, so the
 * selected tenant scopes <em>every</em> operation uniformly — list queries,
 * by-id reads, creates, updates and deletes — not just GETs.
 *
 * <p>Scope rules:
 * <ul>
 *   <li>No {@code X-Tenant-Id} (or {@code parent_cluster}): keep the user's full
 *       tenant memberships. Operators in {@code parent_cluster}/camunda-admin
 *       therefore see all tenants.</li>
 *   <li>A specific {@code X-Tenant-Id}: scope to exactly that tenant. A
 *       non-privileged user that is not a member of it gets 403.</li>
 * </ul>
 */
@Configuration
public class RestApiAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(RestApiAuthFilter.class);

    private static final String CAMUNDA_ADMIN_GROUP = "camunda-admin";

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    @Bean
    public FilterRegistrationBean<Filter> engineRestAuthFilter(IdentityService identityService,
                                                               GatewayJwtAuthenticator gatewayAuth) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new EngineRestBasicAuthFilter(identityService, gatewayAuth, parentClusterId));
        registration.addUrlPatterns("/engine-rest/*");
        registration.setName("engineRestAuthFilter");
        registration.setOrder(1);
        log.info("RestApiAuthFilter registered — Basic + {} gateway-Bearer; tenant scope driven by X-Tenant-Id (parent cluster '{}' sees all)",
                gatewayAuth.isEnabled() ? "enabled" : "disabled", parentClusterId);
        return registration;
    }

    private static class EngineRestBasicAuthFilter implements Filter {

        private final IdentityService identityService;
        private final GatewayJwtAuthenticator gatewayAuth;
        private final String parentClusterId;

        EngineRestBasicAuthFilter(IdentityService identityService, GatewayJwtAuthenticator gatewayAuth,
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

            // Let CORS preflight through
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
                // Resolve the caller's username from whichever scheme they used:
                //   Bearer → a gateway act-as-user token (consumer-ui via luke-auth-engine)
                //   Basic  → username:password against CIBSeven (core-ui, platform extensions)
                // Both schemes converge on the SAME authorization path below.
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

                proceedAsUser(username, httpReq, httpResp, request, response, chain);
            } catch (IllegalArgumentException e) {
                log.debug("Malformed Authorization header");
                sendUnauthorized(httpResp);
            }
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
         * or null (response written). The asserted user must already exist in the
         * engine — a Clerk user that has authenticated but not yet been onboarded
         * gets a clean 403 "not provisioned" rather than acting with no scope.
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
         * Resolve the user's groups + tenants, scope them from {@code X-Tenant-Id},
         * set the engine authentication, and run the request. Shared by both schemes.
         */
        private void proceedAsUser(String username, HttpServletRequest httpReq, HttpServletResponse httpResp,
                                   ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {

            List<String> userTenants = identityService.createTenantQuery()
                    .userMember(username)
                    .list()
                    .stream()
                    .map(Tenant::getId)
                    .toList();
            // Groups must be passed so authorization checks honor grants
            // inherited via group membership (e.g. camunda-admin).
            List<String> groupIds = identityService.createGroupQuery()
                    .groupMember(username)
                    .list()
                    .stream()
                    .map(Group::getId)
                    .toList();

            // Resolve the tenant scope for this request from the header.
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

            // No selection, or explicitly the parent cluster → keep full memberships
            // (operators in parent_cluster / camunda-admin thereby see all tenants).
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

        private void sendNotProvisioned(HttpServletResponse response, String userId) throws IOException {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"User '" + userId
                    + "' is authenticated but not yet onboarded to the engine\"}");
        }
    }
}
