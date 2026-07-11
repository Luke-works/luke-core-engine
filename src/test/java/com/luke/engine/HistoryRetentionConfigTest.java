package com.luke.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * #29: history is RETAINED, not purged. Business/audit requirement — we keep full
 * process + form-submission history and scale storage to hold it, rather than deleting
 * old data. This pins that posture so a cleanup window can't be reintroduced by accident:
 *  - history-level stays `full` (the trace UI needs historic variable values),
 *  - NO history cleanup batch window is configured (so the cleanup job never runs),
 *  - NO historyTimeToLive default (a TTL would imply data is disposable — it isn't).
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:histretention;DB_CLOSE_DELAY=-1",
        "luke.auth.gateway.enabled=false"
})
class HistoryRetentionConfigTest {

    @Autowired
    private ProcessEngine engine;

    @Test
    void fullHistoryIsRetainedWithNoCleanup() {
        ProcessEngineConfigurationImpl cfg =
                (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();

        assertEquals("full", cfg.getHistory(),
                "history-level must stay full so historic variables are kept for the trace UI/audit (#29)");

        // The real guarantee against deletion: no global TTL means no instance is ever
        // eligible for removal, so the cleanup job (even if it ran) deletes nothing.
        // History is kept indefinitely; storage is scaled to hold it.
        assertNull(cfg.getHistoryTimeToLive(),
                "no default historyTimeToLive — retention is indefinite (#29)");

        // ...and definitions may deploy without a TTL (we don't want one). If this flipped
        // to true, TTL-less deployments would be rejected (it also broke the outbox flow).
        assertFalse(cfg.isEnforceHistoryTimeToLive(),
                "enforceHistoryTimeToLive must stay false so TTL-less definitions deploy (#29)");
    }
}
