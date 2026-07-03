package com.luke.engine.capability.access;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.Filter;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * Locks in that the gateway identity-injection filter covers EVERY capability route the
 * {@link CapabilityAccessInterceptor} guards. The two must agree: the interceptor demands
 * X-User-Id, and (when the gateway verifier is enabled) only this filter supplies it from the
 * verified act-as token. A guarded route missing here 401s in deployed environments even for a
 * legitimately-authenticated caller (the bug that hid the merged signature routes on consdev).
 */
class GatewayAuthFilterTest {

    @Test
    void coversAllGuardedCapabilityRoutes() {
        FilterRegistrationBean<Filter> reg = new GatewayAuthFilter().gatewayAuthFilterRegistration(null);
        Collection<String> patterns = reg.getUrlPatterns();

        // Signatures (merged from luke-signature-engine).
        assertTrue(patterns.contains("/api/signature-definitions/*"), patterns.toString());
        assertTrue(patterns.contains("/api/signature-instances/*"), patterns.toString());
        assertTrue(patterns.contains("/api/signatures/*"), patterns.toString());

        // Phone / Voice (Vapi) — the same miss recurred here on consdev; lock it in.
        assertTrue(patterns.contains("/api/phone-calls/*"), patterns.toString());
        assertTrue(patterns.contains("/api/phone-numbers/*"), patterns.toString());
        assertTrue(patterns.contains("/api/phone-settings/*"), patterns.toString());

        // Workflow (definitions/versions lifecycle, catalog, integrations module).
        assertTrue(patterns.contains("/api/workflow/*"), patterns.toString());

        // The pre-existing capability routes stay covered.
        assertTrue(patterns.contains("/api/form-definitions/*"), patterns.toString());
        assertTrue(patterns.contains("/api/form-instances/*"), patterns.toString());
        assertTrue(patterns.contains("/api/emails/*"), patterns.toString());
    }
}
