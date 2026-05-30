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

        // Paths exempt from tenant enforcement
        private static final Set<String> EXEMPT_PATHS = Set.of(
                "/engine-rest/engine",
                "/engine-rest/tenant",
                "/engine-rest/user",
                "/engine-rest/group",
                "/engine-rest/identity",
                "/engine-rest/deployment",
                "/engine-rest/version"
        );

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

            // Check if path is exempt
            String path = httpReq.getRequestURI();
            for (String exempt : EXEMPT_PATHS) {
                if (path.equals(exempt) || path.startsWith(exempt + "/")) {
                    chain.doFilter(request, response);
                    return;
                }
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
