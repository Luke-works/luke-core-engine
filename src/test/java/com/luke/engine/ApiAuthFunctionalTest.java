package com.luke.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Functional test driving the REAL embedded server + servlet filter chain (H2-backed,
 * hermetic — no Docker). Proves {@code ApiAuthFilter} now protects the three endpoints
 * that were previously unauthenticated (GHSA-69jj / -4vg7 / -4gpc), and that a valid
 * operator still gets through.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "DB_URL=jdbc:h2:mem:coreapitest;DB_CLOSE_DELAY=-1",
                "luke.auth.gateway.enabled=false"
        })
class ApiAuthFunctionalTest {

    @Autowired
    private TestRestTemplate rest;

    private HttpEntity<Void> tenant(String tenantId) {
        HttpHeaders h = new HttpHeaders();
        if (tenantId != null) h.set("X-Tenant-Id", tenantId);
        return new HttpEntity<>(h);
    }

    @Test
    void topics_withoutAuth_isUnauthorized() {
        assertEquals(401, rest.getForEntity("/api/topics", String.class).getStatusCode().value());
    }

    @Test
    void formInbox_withoutAuth_isUnauthorized() {
        ResponseEntity<String> r = rest.exchange(
                "/api/form-inbox", HttpMethod.GET, tenant("parent_cluster"), String.class);
        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void processTrace_withoutAuth_isUnauthorized() {
        ResponseEntity<String> r =
                rest.getForEntity("/api/process-trace/does-not-exist", String.class);
        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void topics_withOperatorBasicAuth_isOk() {
        // The Camunda admin (created at boot, admin/admin in the default test profile)
        // is an operator and may read the global topic registry.
        ResponseEntity<String> r = rest.withBasicAuth("admin", "admin")
                .getForEntity("/api/topics", String.class);
        assertEquals(200, r.getStatusCode().value());
    }
}
