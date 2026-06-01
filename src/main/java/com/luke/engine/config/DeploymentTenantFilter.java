package com.luke.engine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rejects deployments that are created without a valid tenant id.
 *
 * A Camunda deployment is created via a multipart/form-data POST to
 * {@code .../engine-rest/deployment/create}, where the tenant is carried in the
 * {@code tenant-id} form field. The generic {@link TenantFilter} deliberately
 * exempts {@code /engine-rest/deployment} (so cross-tenant reads/deletes still
 * work), so deployment <em>creation</em> is guarded here instead.
 *
 * The guard requires the {@code tenant-id} to be (a) present/non-blank and
 * (b) an existing tenant in the engine ({@link IdentityService}). A random,
 * unknown tenant id is rejected.
 */
@Configuration
public class DeploymentTenantFilter {

    private static final Logger log = LoggerFactory.getLogger(DeploymentTenantFilter.class);

    private final IdentityService identityService;

    public DeploymentTenantFilter(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Bean
    public FilterRegistrationBean<Filter> deploymentTenantEnforcementFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequireTenantOnDeploymentFilter(identityService));
        registration.addUrlPatterns("/engine-rest/*");
        registration.setName("deploymentTenantEnforcementFilter");
        registration.setOrder(3); // after auth (1) and tenant enforcement (2)
        log.info("DeploymentTenantFilter registered — enforcing existing tenant-id on POST .../deployment/create");
        return registration;
    }

    private static class RequireTenantOnDeploymentFilter implements Filter {

        private static final Logger log = LoggerFactory.getLogger(RequireTenantOnDeploymentFilter.class);
        private static final ObjectMapper MAPPER = new ObjectMapper();

        // Matches the multipart part:  name="tenant-id" [more part headers] CRLF CRLF <value> CRLF --boundary
        private static final Pattern TENANT_ID_PART = Pattern.compile(
                "name=\"tenant-id\"[\\s\\S]*?\\r?\\n\\r?\\n([\\s\\S]*?)\\r?\\n--",
                Pattern.CASE_INSENSITIVE);

        private final IdentityService identityService;

        RequireTenantOnDeploymentFilter(IdentityService identityService) {
            this.identityService = identityService;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {

            HttpServletRequest httpReq = (HttpServletRequest) request;
            HttpServletResponse httpResp = (HttpServletResponse) response;

            String path = httpReq.getRequestURI();

            // Only guard deployment creation (POST .../deployment/create); pass everything else through.
            if (!"POST".equalsIgnoreCase(httpReq.getMethod()) || path == null || !path.endsWith("/deployment/create")) {
                chain.doFilter(request, response);
                return;
            }

            // Buffer the body so we can inspect it and still hand it to the engine.
            byte[] body = httpReq.getInputStream().readAllBytes();
            String tenantId = extractTenantId(body);

            if (tenantId == null || tenantId.isBlank()) {
                log.warn("DeploymentTenantFilter: REJECT deploy without tenant id (path={}, {} bytes)", path, body.length);
                sendError(httpResp, "missing tenant id",
                        "Every deployment must include a non-empty 'tenant-id' form field. "
                                + "Untenanted deployments are not allowed.");
                return;
            }

            if (!tenantExists(tenantId)) {
                log.warn("DeploymentTenantFilter: REJECT deploy with unknown tenant '{}' (path={})", tenantId, path);
                sendError(httpResp, "unknown tenant '" + tenantId + "'",
                        "The 'tenant-id' '" + tenantId + "' does not match any existing tenant.");
                return;
            }

            log.info("DeploymentTenantFilter: allow deploy (tenant='{}', path={})", tenantId, path);
            chain.doFilter(new CachedBodyRequest(httpReq, body), response);
        }

        private String extractTenantId(byte[] body) {
            // Multipart field values are short ASCII; ISO-8859-1 keeps byte offsets intact.
            String text = new String(body, StandardCharsets.ISO_8859_1);
            Matcher matcher = TENANT_ID_PART.matcher(text);
            return matcher.find() ? matcher.group(1).trim() : null;
        }

        /**
         * True if the tenant exists. The existence check runs with engine
         * authentication cleared (then restored) so it isn't filtered by the
         * deploying user's tenant-read authorizations. Fails closed (returns
         * false) if the lookup errors.
         */
        private boolean tenantExists(String tenantId) {
            Authentication current = identityService.getCurrentAuthentication();
            try {
                identityService.clearAuthentication();
                return identityService.createTenantQuery().tenantId(tenantId).count() > 0;
            } catch (Exception e) {
                log.error("DeploymentTenantFilter: tenant lookup failed for '{}', rejecting deploy", tenantId, e);
                return false;
            } finally {
                identityService.setAuthentication(current);
            }
        }

        private void sendError(HttpServletResponse response, String message, String detail) throws IOException {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", "Deployment rejected: " + message);
            error.put("error", detail);
            MAPPER.writeValue(response.getWriter(), error);
        }
    }

    /** Re-serves a previously buffered request body so the engine can read it after inspection. */
    private static class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return buffer.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { /* synchronous */ }
                @Override public int read() { return buffer.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.ISO_8859_1));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
