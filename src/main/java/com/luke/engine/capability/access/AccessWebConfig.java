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
        // Signatures (merged from luke-signature-engine). The authed design-time +
        // runtime APIs are capability-guarded; the public per-recipient signing routes
        // (/api/public/sign/**, /api/public/sign-instance/**) are token-authenticated by
        // design and intentionally NOT listed here.
        registry.addInterceptor(new CapabilityAccessInterceptor(access).forCapability("SIGNATURES"))
                .addPathPatterns("/api/signature-definitions/**", "/api/signature-instances/**",
                        "/api/signatures/**");
        // Phone / Voice (Vapi). The authed tenant APIs are capability-guarded; the public Vapi
        // webhook (/api/public/phone/**) is shared-secret-authenticated by design and intentionally
        // NOT listed here, and the internal outbound endpoint (/api/internal/phone-calls) sits behind
        // the shared-secret InternalAuthFilter.
        registry.addInterceptor(new CapabilityAccessInterceptor(access).forCapability("PHONE"))
                .addPathPatterns("/api/phone-calls/**", "/api/phone-numbers/**", "/api/phone-settings/**");
        // Workflow (composes other capabilities via a step-type registry; integrations via Nango).
        registry.addInterceptor(new CapabilityAccessInterceptor(access).forCapability("WORKFLOW"))
                .addPathPatterns("/api/workflow/**");
        // Secrets are internal-only for now (served via /api/internal/secrets behind the
        // shared-secret filter). When the tenant-facing /api/secrets API is re-opened,
        // re-add a SECRETS interceptor here.
    }
}
