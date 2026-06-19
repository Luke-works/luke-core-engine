package com.luke.engine.capability.access;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the capability gatekeeper onto the feature routes. Each feature is guarded
 * by its capability: GET needs read, mutations need read-write. Forms routes →
 * "FORMS"; tenant email routes → "EMAIL".
 *
 * <p>A second {@link WebMvcConfigurer} alongside the CORS one — Spring composes
 * them. New capabilities register their own guarded path prefixes here.
 */
@Configuration
public class AccessWebConfig implements WebMvcConfigurer {

    private final CapabilityAccessService access;

    public AccessWebConfig(CapabilityAccessService access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CapabilityAccessInterceptor(access).forCapability("FORMS"))
                .addPathPatterns("/api/form-definitions/**", "/api/form-instances/**");
        registry.addInterceptor(new CapabilityAccessInterceptor(access).forCapability("EMAIL"))
                .addPathPatterns("/api/emails/**", "/api/email-servers/**", "/api/email-verification/**",
                        "/api/email-templates/**");
        // Secrets are internal-only for now (served via /api/internal/secrets behind the
        // shared-secret filter). When the tenant-facing /api/secrets API is re-opened,
        // re-add a SECRETS interceptor here.
    }
}
