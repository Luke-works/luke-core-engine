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
 * by validating credentials against CIBSeven's IdentityService.
 */
@Configuration
public class RestApiAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(RestApiAuthFilter.class);

    @Bean
    public FilterRegistrationBean<Filter> engineRestAuthFilter(IdentityService identityService) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new EngineRestBasicAuthFilter(identityService));
        registration.addUrlPatterns("/engine-rest/*");
        registration.setName("engineRestAuthFilter");
        registration.setOrder(1);
        return registration;
    }

    private static class EngineRestBasicAuthFilter implements Filter {

        private final IdentityService identityService;

        EngineRestBasicAuthFilter(IdentityService identityService) {
            this.identityService = identityService;
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

                if (identityService.checkPassword(username, password)) {
                    List<String> tenantIds = identityService.createTenantQuery()
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
                    identityService.setAuthentication(username, groupIds, tenantIds);
                    try {
                        chain.doFilter(request, response);
                    } finally {
                        identityService.clearAuthentication();
                    }
                } else {
                    log.warn("Authentication failed for user: {}", username);
                    sendUnauthorized(httpResp);
                }
            } catch (IllegalArgumentException e) {
                log.debug("Malformed Authorization header");
                sendUnauthorized(httpResp);
            }
        }

        private void sendUnauthorized(HttpServletResponse response) throws IOException {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Luke Core Engine\"");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Valid credentials required\"}");
        }
    }
}
