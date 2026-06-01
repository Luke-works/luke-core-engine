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
    public FilterRegistrationBean<Filter> engineRestAuthFilter(IdentityService identityService) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new EngineRestBasicAuthFilter(identityService, parentClusterId));
        registration.addUrlPatterns("/engine-rest/*");
        registration.setName("engineRestAuthFilter");
        registration.setOrder(1);
        log.info("RestApiAuthFilter registered — tenant scope driven by X-Tenant-Id (parent cluster '{}' sees all)", parentClusterId);
        return registration;
    }

    private static class EngineRestBasicAuthFilter implements Filter {

        private final IdentityService identityService;
        private final String parentClusterId;

        EngineRestBasicAuthFilter(IdentityService identityService, String parentClusterId) {
            this.identityService = identityService;
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

            if (authHeader == null || !authHeader.toLowerCase().startsWith("basic ")) {
                sendUnauthorized(httpResp);
                return;
            }

            try {
                String base64Credentials = authHeader.substring(6);
                String decoded = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
                int colonIndex = decoded.indexOf(':');
                if (colonIndex < 0) {
                    sendUnauthorized(httpResp);
                    return;
                }

                String username = decoded.substring(0, colonIndex);
                String password = decoded.substring(colonIndex + 1);

                if (!identityService.checkPassword(username, password)) {
                    log.warn("Authentication failed for user: {}", username);
                    sendUnauthorized(httpResp);
                    return;
                }

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
            } catch (IllegalArgumentException e) {
                log.debug("Malformed Authorization header");
                sendUnauthorized(httpResp);
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
    }
}
