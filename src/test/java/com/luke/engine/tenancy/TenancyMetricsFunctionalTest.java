package com.luke.engine.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.identity.Tenant;
import org.cibseven.bpm.engine.identity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * #39: the cross-tenant metrics endpoint is one operator-only server call (H2, no Docker).
 * Operators get per-tenant counts + totals; non-operators are forbidden; unauthenticated
 * is rejected.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "DB_URL=jdbc:h2:mem:metricstest;DB_CLOSE_DELAY=-1",
                "luke.auth.gateway.enabled=false"
        })
class TenancyMetricsFunctionalTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private ProcessEngine engine;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        IdentityService is = engine.getIdentityService();
        if (is.createTenantQuery().tenantId("metrics-tenant-x").count() == 0) {
            Tenant t = is.newTenant("metrics-tenant-x");
            t.setName("Metrics Tenant X");
            is.saveTenant(t);
        }
        if (is.createUserQuery().userId("metrics-bob").count() == 0) {
            User u = is.newUser("metrics-bob");
            u.setPassword("pw");
            is.saveUser(u);
        }
        if (is.createTenantQuery().tenantId("metrics-tenant-x").userMember("metrics-bob").count() == 0) {
            is.createTenantUserMembership("metrics-tenant-x", "metrics-bob");
        }
    }

    @Test
    void operator_getsAllTenantsWithConsistentTotals() throws Exception {
        ResponseEntity<String> r = rest.withBasicAuth("admin", "admin")
                .getForEntity("/api/tenancy/metrics", String.class);

        assertEquals(200, r.getStatusCode().value());
        JsonNode body = json.readTree(r.getBody());
        JsonNode tenants = body.get("tenants");
        assertTrue(tenants.isArray() && tenants.size() >= 1);
        // totals.tenants matches the per-tenant array length (no silent cap)
        assertEquals(tenants.size(), body.get("totals").get("tenants").asLong());
        // the seeded tenant is present with at least its one member counted
        boolean found = false;
        for (JsonNode t : tenants) {
            if ("metrics-tenant-x".equals(t.get("tenant").get("id").asText())) {
                found = true;
                assertTrue(t.get("users").asLong() >= 1);
            }
        }
        assertTrue(found, "seeded tenant should appear in the metrics");
    }

    @Test
    void nonOperator_isForbidden() {
        ResponseEntity<String> r = rest.withBasicAuth("metrics-bob", "pw")
                .getForEntity("/api/tenancy/metrics", String.class);
        assertEquals(403, r.getStatusCode().value());
    }

    @Test
    void unauthenticated_isUnauthorized() {
        ResponseEntity<String> r = rest.getForEntity("/api/tenancy/metrics", String.class);
        assertEquals(401, r.getStatusCode().value());
    }
}
