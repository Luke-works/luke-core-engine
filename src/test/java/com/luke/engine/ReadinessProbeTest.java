package com.luke.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * #36: the readiness/liveness probe split must be live and the readiness group must be
 * DB-aware, so Render (healthCheckPath = /actuator/health/readiness) stops routing to an
 * instance whose DB is unreachable or that is draining during graceful shutdown (#44).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "DB_URL=jdbc:h2:mem:readinessprobe;DB_CLOSE_DELAY=-1",
                "luke.auth.gateway.enabled=false"
        })
class ReadinessProbeTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void readinessProbeIsUpAndDbAware() {
        ResponseEntity<String> r = rest.getForEntity("/actuator/health/readiness", String.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().contains("\"status\":\"UP\""), "readiness should be UP when healthy");
        // show-details: always → the DB component must appear, proving readiness is DB-aware.
        assertTrue(r.getBody().contains("\"db\""),
                "readiness group must include the datasource (#36)");
    }

    @Test
    void livenessProbeIsUp() {
        ResponseEntity<String> r = rest.getForEntity("/actuator/health/liveness", String.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().contains("\"status\":\"UP\""));
    }
}
