package com.luke.engine.tenant;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Servlet filter that captures X-Tenant-Id header and stores it
 * in TenantContext for the duration of the request.
 * This makes the tenant available to Hibernate filters and entity listeners.
 */
@Configuration
public class TenantInterceptor {

    @Bean
    public FilterRegistrationBean<Filter> tenantContextFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new TenantContextFilter());
        reg.addUrlPatterns("/api/*", "/engine-rest/*");
        reg.setName("tenantContextFilter");
        reg.setOrder(0); // runs before auth and tenant enforcement filters
        return reg;
    }

    private static class TenantContextFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest httpReq = (HttpServletRequest) request;
            String tenantId = httpReq.getHeader("X-Tenant-Id");
            if (tenantId == null || tenantId.isBlank()) {
                tenantId = httpReq.getParameter("tenantIdIn");
            }
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.set(tenantId);
            }
            try {
                chain.doFilter(request, response);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
