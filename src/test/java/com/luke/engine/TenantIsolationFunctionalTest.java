package com.luke.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.identity.Tenant;
import org.finos.fluxnova.bpm.engine.identity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * #41: end-to-end tenant isolation on the real filter chain (H2, no Docker).
 * A scoped, non-operator user (member of one tenant) cannot act in another tenant,
 * the tenant-id requirement is enforced on non-exempt paths, and exempt paths bypass
 * that requirement (their isolation is Camunda authorization — see TenantFilter docs).
 *
 * Seeding runs with no authenticated user on the thread, so it bypasses Camunda
 * authorization (which is enabled in app config) — the HTTP requests below do not.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "DB_URL=jdbc:h2:mem:coreapitest;DB_CLOSE_DELAY=-1",
                "luke.auth.gateway.enabled=false"
        })
class TenantIsolationFunctionalTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ProcessEngine engine;

    @BeforeEach
    void seed() {
        IdentityService is = engine.getIdentityService();
        for (String t : new String[] {"iso-tenant-a", "iso-tenant-b"}) {
            if (is.createTenantQuery().tenantId(t).count() == 0) {
                Tenant tenant = is.newTenant(t);
                tenant.setName(t);
                is.saveTenant(tenant);
            }
        }
        if (is.createUserQuery().userId("iso-alice").count() == 0) {
            User u = is.newUser("iso-alice");
            u.setPassword("pw");
            is.saveUser(u);
        }
        if (is.createTenantQuery().tenantId("iso-tenant-a").userMember("iso-alice").count() == 0) {
            is.createTenantUserMembership("iso-tenant-a", "iso-alice");
        }
    }

    private HttpEntity<Void> withTenant(String tenantId) {
        HttpHeaders h = new HttpHeaders();
        if (tenantId != null) h.set("X-Tenant-Id", tenantId);
        return new HttpEntity<>(h);
    }

    @Test
    void scopedUser_actingAsTenantTheyDoNotBelongTo_isForbidden() {
        ResponseEntity<String> r = rest.withBasicAuth("iso-alice", "pw").exchange(
                "/engine-rest/process-instance", HttpMethod.GET, withTenant("iso-tenant-b"), String.class);
        assertEquals(403, r.getStatusCode().value()); // cross-tenant act blocked at the gateway
    }

    @Test
    void scopedUser_actingAsOwnTenant_isAllowed() {
        ResponseEntity<String> r = rest.withBasicAuth("iso-alice", "pw").exchange(
                "/engine-rest/process-instance", HttpMethod.GET, withTenant("iso-tenant-a"), String.class);
        assertNotEquals(403, r.getStatusCode().value());
        assertNotEquals(401, r.getStatusCode().value());
    }

    @Test
    void nonExemptPath_withoutTenant_isBadRequest() {
        ResponseEntity<String> r = rest.withBasicAuth("iso-alice", "pw")
                .getForEntity("/engine-rest/process-instance", String.class);
        assertEquals(400, r.getStatusCode().value()); // tenant id is required
    }

    @Test
    void exemptPath_withoutTenant_isNotBadRequest() {
        // /engine-rest/user is exempt from the tenant-id requirement (isolation there is
        // Camunda authz). It must not be rejected by the TenantFilter for lacking a tenant.
        ResponseEntity<String> r = rest.withBasicAuth("iso-alice", "pw")
                .getForEntity("/engine-rest/user", String.class);
        assertNotEquals(400, r.getStatusCode().value());
    }
}
