package com.luke.engine.capability.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * Functional test (real HTTP, H2): with no {@code luke.internal.shared-secret}
 * configured, the secret-bearing {@code /api/internal/**} routes must FAIL CLOSED
 * (503), not pass through unauthenticated (guards GHSA-j9gx).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"DB_URL=jdbc:h2:mem:capfailclosed;DB_CLOSE_DELAY=-1"})
class CapabilityInternalAuthFailClosedTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void internalRoutes_refused503_whenSecretUnset() {
        assertEquals(503, rest.getForEntity(
                "/api/internal/secrets/resolve?tenantId=t&name=n", String.class)
                .getStatusCode().value());
    }
}
