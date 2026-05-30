package com.luke.engine.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

@Configuration
public class DocsResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/documentation/**")
                .addResourceLocations("classpath:/static/documentation/")
                .resourceChain(true);
    }

    /**
     * Filter that redirects extensionless doc URLs to .html versions.
     * e.g. /documentation/guide/capabilities → /documentation/guide/capabilities.html
     */
    @Bean
    public FilterRegistrationBean<Filter> docsHtmlRedirectFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new DocsHtmlFilter());
        reg.addUrlPatterns("/documentation/*");
        reg.setName("docsHtmlRedirectFilter");
        reg.setOrder(-10);
        return reg;
    }

    private static class DocsHtmlFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;
            String path = req.getRequestURI();

            // Skip if already has extension or is root /documentation/
            if (path.contains(".") || path.endsWith("/")) {
                chain.doFilter(request, response);
                return;
            }

            // Check if .html version exists
            String resourcePath = "static" + path + ".html";
            if (new ClassPathResource(resourcePath).exists()) {
                resp.sendRedirect(path + ".html");
                return;
            }

            chain.doFilter(request, response);
        }
    }
}
