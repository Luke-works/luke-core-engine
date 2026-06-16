package com.luke.engine.backfill;

import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs every registered {@link Backfill} once the application is ready, ordered by
 * {@link Backfill#order()}. Each runs independently: a failure is logged and the next
 * still runs. Backfills are idempotent, so re-running on every boot is safe — most are
 * no-ops after the first time.
 *
 * <p>Ordered to run after the identity initializers (parent cluster + admin user) so
 * the records those create are present when backfills inspect them.
 */
@Component
@Order(1000)
public class BackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);

    private final List<Backfill> backfills;

    public BackfillRunner(List<Backfill> backfills) {
        this.backfills = backfills;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runAll() {
        if (backfills.isEmpty()) {
            return;
        }
        log.info("Running {} backfill(s)…", backfills.size());
        backfills.stream()
                .sorted(Comparator.comparingInt(Backfill::order))
                .forEach(this::runOne);
    }

    private void runOne(Backfill backfill) {
        try {
            int changed = backfill.run();
            if (changed > 0) {
                log.info("Backfill '{}' applied {} change(s)", backfill.name(), changed);
            } else {
                log.debug("Backfill '{}': nothing to do", backfill.name());
            }
        } catch (Exception e) {
            log.error("Backfill '{}' failed: {}", backfill.name(), e.getMessage(), e);
        }
    }
}
