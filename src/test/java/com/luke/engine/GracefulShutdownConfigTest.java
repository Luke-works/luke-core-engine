package com.luke.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.core.env.Environment;

/**
 * #44: deploys must drain, not hard-stop. Pin the graceful-shutdown wiring so it can't
 * silently regress (in-flight HTTP requests + acquired Camunda jobs would otherwise be
 * cut off on every Render redeploy/scale-down).
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:shutdowncfg;DB_CLOSE_DELAY=-1",
        "luke.auth.gateway.enabled=false"
})
class GracefulShutdownConfigTest {

    @Autowired
    private ServerProperties serverProperties;

    @Autowired
    private Environment env;

    @Test
    void gracefulShutdownIsEnabled() {
        assertEquals(Shutdown.GRACEFUL, serverProperties.getShutdown(),
                "server.shutdown must be graceful so in-flight requests drain on SIGTERM (#44)");
    }

    @Test
    void shutdownPhaseTimeoutIsBounded() {
        assertEquals("30s", env.getProperty("spring.lifecycle.timeout-per-shutdown-phase"),
                "a bounded shutdown-phase timeout must be set (#44)");
    }

    @Test
    void healthProbesAreEnabled() {
        // Readiness probe exists so it can flip OUT_OF_SERVICE during drain (#44/#36).
        assertTrue(Boolean.parseBoolean(env.getProperty("management.endpoint.health.probes.enabled")),
                "health probes must be enabled for readiness-based draining (#44)");
    }
}
