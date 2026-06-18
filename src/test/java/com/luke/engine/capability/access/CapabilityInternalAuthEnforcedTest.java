package com.luke.engine.capability.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * Functional test (real HTTP, H2): with the shared secret configured, the
 * {@code /api/internal/**} routes require a matching {@code X-Internal-Key}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "DB_URL=jdbc:h2:mem:capenforced;DB_CLOSE_DELAY=-1",
                "luke.internal.shared-secret=test-secret"
        })
class CapabilityInternalAuthEnforcedTest {

    @Autowired
    private TestRestTemplate rest;

    private static final String PATH = "/api/internal/secrets/resolve?tenantId=t&name=n";

    @Test
    void missingKey_isUnauthorized() {
        assertEquals(401, rest.getForEntity(PATH, String.class).getStatusCode().value());
    }

    @Test
    void wrongKey_isUnauthorized() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Key", "not-the-secret");
        assertEquals(401, rest.exchange(PATH, HttpMethod.GET, new HttpEntity<>(h), String.class)
                .getStatusCode().value());
    }
}
