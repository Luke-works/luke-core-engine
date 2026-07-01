package com.luke.engine.workflow.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the inbound-event outbox (WF-12), mirroring {@code PhoneCallProcessOutboxConsumer}.
 * Each QUEUED Nango event is correlated to Camunda: SENT if it started/advanced one or more
 * instances, SKIPPED if nothing was waiting (a valid outcome — the event had nowhere to go),
 * or FAILED (retried next poll) on a transient error.
 *
 * <p>HA note: with &gt;1 engine node run this on exactly ONE — set
 * {@code LUKE_WORKFLOW_OUTBOX_ENABLED=false} on the others so two pollers don't race a row.
 */
@Component
public class IntegrationEventOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(IntegrationEventOutboxConsumer.class);

    private final IntegrationEventOutboxRepository outbox;
    private final IntegrationEventCorrelator correlator;

    @Value("${luke.workflow.outbox-enabled:true}")
    private boolean enabled;

    public IntegrationEventOutboxConsumer(IntegrationEventOutboxRepository outbox,
            IntegrationEventCorrelator correlator) {
        this.outbox = outbox;
        this.correlator = correlator;
    }

    @Scheduled(fixedDelayString = "${luke.workflow.outbox-poll-ms:2000}")
    public void drain() {
        if (!enabled) return;
        for (IntegrationEventOutbox row : outbox.findByStateOrderByCreatedAtAsc(IntegrationEventOutbox.QUEUED)) {
            deliver(row);
        }
    }

    void deliver(IntegrationEventOutbox row) {
        try {
            int correlated = correlator.correlate(
                    row.getTenantId(), row.getMessageName(), row.getCorrelationKey(), row.getPayloadJson());
            row.setState(correlated > 0 ? IntegrationEventOutbox.SENT : IntegrationEventOutbox.SKIPPED);
            row.setErrorMessage(null);
            outbox.save(row);
            if (correlated == 0) {
                log.debug("Nango event {} ({}) matched no waiting workflow — skipped",
                        row.getId(), row.getMessageName());
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            row.setErrorMessage(msg);
            row.setRetryCount(row.getRetryCount() + 1);
            outbox.save(row);
            log.warn("Inbound event correlation failed for {} (tenant {}, message {}): {}",
                    row.getId(), row.getTenantId(), row.getMessageName(), msg);
        }
    }
}
