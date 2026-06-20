package com.luke.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * #29: history cleanup must actually be configured on the engine. Without a batch
 * window the cleanup job never runs and historyTimeToLive (P180D) is never enforced,
 * so ACT_HI_* grows until the 1GB Postgres disk fills. This pins the window + strategy
 * so the config can't silently regress.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:histcleanup;DB_CLOSE_DELAY=-1",
        "luke.auth.gateway.enabled=false"
})
class HistoryCleanupConfigTest {

    @Autowired
    private ProcessEngine engine;

    @Test
    void historyCleanupBatchWindowIsConfigured() {
        ProcessEngineConfigurationImpl cfg =
                (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();

        assertEquals("02:00", cfg.getHistoryCleanupBatchWindowStartTime(),
                "cleanup batch window start must be set so the cleanup job runs (#29)");
        assertEquals("04:00", cfg.getHistoryCleanupBatchWindowEndTime(),
                "cleanup batch window end must be set (#29)");
        assertEquals("removalTimeBased", cfg.getHistoryCleanupStrategy(),
                "expected the performant removal-time-based cleanup strategy (#29)");
    }

    @Test
    void historyTtlIsEnforceableDefault() {
        ProcessEngineConfigurationImpl cfg =
                (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        // A global default TTL must exist for the cleanup job to have something to enforce.
        assertNotNull(cfg.getHistoryTimeToLive(), "a default historyTimeToLive must be set (#29)");
        assertEquals("P180D", cfg.getHistoryTimeToLive(), "retention decision is 180 days (#29)");
    }
}
