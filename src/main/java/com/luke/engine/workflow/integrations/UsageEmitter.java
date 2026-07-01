package com.luke.engine.workflow.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The metering emit site (WF-11). Records a usage event exactly-once via its idempotency
 * key — a retried connector execution reuses the key, so it counts once. Runs in the
 * caller's transaction (the executor's), so on rollback the emit rolls back too. WF-15
 * forwards unbilled events to Stripe.
 */
@Component
public class UsageEmitter {

    private static final Logger log = LoggerFactory.getLogger(UsageEmitter.class);
    public static final String TYPE_EXECUTION = "execution";

    private final IntegrationUsageEventRepository events;

    public UsageEmitter(IntegrationUsageEventRepository events) {
        this.events = events;
    }

    /** Meter one successful connector execution (idempotent on {@code idempotencyKey}). */
    public void emitExecution(String tenantId, String connectionId, String idempotencyKey) {
        if (idempotencyKey != null && events.existsByIdempotencyKey(idempotencyKey)) {
            return;
        }
        try {
            events.save(new IntegrationUsageEvent(tenantId, connectionId, TYPE_EXECUTION, idempotencyKey));
        } catch (Exception e) {
            // A unique-constraint race means someone else already emitted it — fine.
            log.debug("usage emit skipped (duplicate key {}): {}", idempotencyKey, e.getMessage());
        }
    }
}
