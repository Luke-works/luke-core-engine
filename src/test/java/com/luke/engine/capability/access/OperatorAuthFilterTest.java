package com.luke.engine.capability.access;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.Filter;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * #50: the destructive privileged admin routes must be behind the operator filter.
 * DELETE /api/users/{userId} purges a user's grants platform-wide and was covered by
 * no auth filter at all — assert the registration now includes /api/users/*.
 */
class OperatorAuthFilterTest {

    @Test
    void privilegedAdminRoutesAreCovered() {
        FilterRegistrationBean<Filter> reg =
                new OperatorAuthFilter().operatorAuthFilterRegistration("op", "pw");
        Collection<String> patterns = reg.getUrlPatterns();
        assertTrue(patterns.contains("/api/users/*"),
                "DELETE /api/users/{userId} must be operator-protected (#50)");
        assertTrue(patterns.contains("/api/tenants/*"), "tenant admin routes must stay protected");
        assertTrue(patterns.contains("/api/capabilities"), "catalog writes must stay protected");
    }
}
