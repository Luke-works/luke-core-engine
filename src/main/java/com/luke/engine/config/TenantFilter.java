package com.luke.engine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Enforces tenant identification on all /engine-rest/* requests.
 * Tenant can be provided via:
 *   - Header:      X-Tenant-Id
 *   - Query param: tenantIdIn
 *
 * Certain paths are exempt (tenant listing, engine info, identity).
 * If no tenant info is found, returns 400 with error details.
 */
@Configuration
public class TenantFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

    /**
     * Paths exempt from the tenant-id REQUIREMENT (#41) — they may be called without
     * an {@code X-Tenant-Id}. They are NOT unauthenticated and NOT unscoped:
     * {@link RestApiAuthFilter} (order 1, runs first) still authenticates the caller
     * and calls {@code identityService.setAuthentication(user, groups, scopeTenants)},
     * so CIBSeven's native tenant checks + authorization ({@code authorization.enabled=true})
     * apply on these paths too. Each is exempt because it is identity/engine management,
     * not tenant-scoped runtime data; the control that REPLACES tenant enforcement here
     * is therefore Camunda authorization seeded per (tenant, user):
     * <ul>
     *   <li>{@code /engine-rest/engine}  – engine list / login probe (global, no tenant)</li>
     *   <li>{@code /engine-rest/version} – REST API version (global, static)</li>
     *   <li>{@code /engine-rest/tenant}  – tenant management/listing (defines tenants); Camunda Tenant authz</li>
     *   <li>{@code /engine-rest/user}    – user management; cross-tenant reads gated by Camunda User READ authz</li>
     *   <li>{@code /engine-rest/group}   – group management; gated by Camunda Group READ authz</li>
     *   <li>{@code /engine-rest/identity}– identity (verify / group-info); gated by authz</li>
     *   <li>{@code /engine-rest/deployment} – create is tenant-checked by {@link DeploymentTenantFilter};
     *       reads/deletes are exempt by design, gated by Camunda Deployment authz</li>
     * </ul>
     * NOTE: adding a path here BROADENS what is reachable without a tenant — it must
     * come with an explicit rationale + the replacing control above, and
     * {@code TenantFilterExemptionTest} pins this set so a change is deliberate.
     */
    static final Set<String> EXEMPT_PATHS = Set.of(
            "/engine-rest/engine",
            "/engine-rest/tenant",
            "/engine-rest/user",
            "/engine-rest/group",
            "/engine-rest/identity",
            "/engine-rest/deployment",
            "/engine-rest/version"
    );

    /**
     * True if {@code path} is an exempt path exactly, or a sub-resource of one
     * ({@code prefix + "/"}). Anchored so e.g. {@code /engine-rest/users} is NOT
     * treated as {@code /engine-rest/user}.
     */
    static boolean isExemptPath(String path) {
        if (path == null) return false;
        for (String exempt : EXEMPT_PATHS) {
            if (path.equals(exempt) || path.startsWith(exempt + "/")) return true;
        }
        return false;
    }

    @Bean
    public FilterRegistrationBean<Filter> tenantEnforcementFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TenantEnforcementFilter());
        registration.addUrlPatterns("/engine-rest/*");
        registration.setName("tenantEnforcementFilter");
        registration.setOrder(2); // runs after auth filter (order 1)
        return registration;
    }

    private static class TenantEnforcementFilter implements Filter {

        private static final ObjectMapper MAPPER = new ObjectMapper();

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

            // Check if path is exempt (still authenticated + scoped by RestApiAuthFilter;
            // isolation there relies on Camunda authorization — see EXEMPT_PATHS docs).
            String path = httpReq.getRequestURI();
            if (isExemptPath(path)) {
                chain.doFilter(request, response);
                return;
            }

            // Look for tenant in header or query param
            String tenantId = httpReq.getHeader("X-Tenant-Id");

            if (tenantId == null || tenantId.isBlank()) {
                tenantId = httpReq.getParameter("tenantIdIn");
            }

            if (tenantId == null || tenantId.isBlank()) {
                log.debug("No tenant info provided for: {} {}", httpReq.getMethod(), path);
                sendTenantError(httpResp);
                return;
            }

            // Tenant found — pass through
            chain.doFilter(request, response);
        }

        private void sendTenantError(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");

            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", "No tenant info provided");
            error.put("error", "Tenant identification is required for all engine operations. " +
                    "Provide tenant via 'X-Tenant-Id' header or 'tenantIdIn' query parameter.");

            MAPPER.writeValue(response.getWriter(), error);
        }
    }
}
