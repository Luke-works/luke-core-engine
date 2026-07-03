package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * #41 (AC3): pins the tenant-exemption denylist so adding a path — which broadens
 * what is reachable without a tenant — is a deliberate, reviewed change, and verifies
 * the matching is anchored (a sub-resource, not a loose prefix).
 */
class TenantFilterExemptionTest {

    @Test
    void exemptSetIsExactlyTheDocumentedPaths() {
        // If this fails, a path was added/removed: update EXEMPT_PATHS' rationale doc
        // and confirm the replacing control (Camunda authz) before changing this set.
        assertEquals(
                Set.of(
                        "/engine-rest/engine",
                        "/engine-rest/version",
                        "/engine-rest/tenant",
                        "/engine-rest/user",
                        "/engine-rest/group",
                        "/engine-rest/identity",
                        "/engine-rest/deployment"),
                TenantFilter.EXEMPT_PATHS);
    }

    @Test
    void matchesExactPathAndSubResources() {
        assertTrue(TenantFilter.isExemptPath("/engine-rest/user"));
        assertTrue(TenantFilter.isExemptPath("/engine-rest/user/alice"));
        assertTrue(TenantFilter.isExemptPath("/engine-rest/deployment/d1/resources"));
    }

    @Test
    void doesNotMatchLookalikeOrTenantScopedPaths() {
        // Anchored: '/user' must not swallow '/users' or '/user-operation'.
        assertFalse(TenantFilter.isExemptPath("/engine-rest/users"));
        assertFalse(TenantFilter.isExemptPath("/engine-rest/process-instance"));
        assertFalse(TenantFilter.isExemptPath("/engine-rest/task"));
        assertFalse(TenantFilter.isExemptPath(null));
    }
}
