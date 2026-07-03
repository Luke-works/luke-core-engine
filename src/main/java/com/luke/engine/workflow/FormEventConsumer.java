package com.luke.engine.workflow;

import com.luke.engine.capability.form.FormEventOutbox;
import com.luke.engine.capability.form.FormEventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the forms→workflow event outbox, mirroring {@code IntegrationEventOutboxConsumer}.
 * Each QUEUED form event is correlated: SENT if it started/advanced ≥1 workflow, SKIPPED if
 * nothing subscribed or waited (a valid no-op), or FAILED (retried next poll) on a transient
 * error.
 *
 * <p>HA note: with &gt;1 engine node run this on exactly ONE — set
 * {@code LUKE_WORKFLOW_OUTBOX_ENABLED=false} on the others (same flag as the integration
 * inbound rail) so two pollers don't race a row.
 */
@Component
public class FormEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(FormEventConsumer.class);

    private final FormEventOutboxRepository outbox;
    private final FormEventCorrelator correlator;

    @Value("${luke.workflow.outbox-enabled:true}")
    private boolean enabled;

    public FormEventConsumer(FormEventOutboxRepository outbox, FormEventCorrelator correlator) {
        this.outbox = outbox;
        this.correlator = correlator;
    }

    @Scheduled(fixedDelayString = "${luke.workflow.outbox-poll-ms:2000}")
    public void drain() {
        if (!enabled) return;
        for (FormEventOutbox row : outbox.findByStateOrderByCreatedAtAsc(FormEventOutbox.QUEUED)) {
            deliver(row);
        }
    }

    void deliver(FormEventOutbox row) {
        try {
            int correlated = correlator.correlate(
                    row.getTenantId(), row.getEventType(), row.getFormCode(),
                    row.getInstanceId(), row.getPayloadJson());
            row.setState(correlated > 0 ? FormEventOutbox.SENT : FormEventOutbox.SKIPPED);
            row.setErrorMessage(null);
            outbox.save(row);
            if (correlated == 0) {
                log.debug("Form event {} ({}) matched no workflow — skipped", row.getId(), row.getEventType());
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            row.setErrorMessage(msg);
            row.setRetryCount(row.getRetryCount() + 1);
            outbox.save(row);
            log.warn("Form event correlation failed for {} (tenant {}, event {}): {}",
                    row.getId(), row.getTenantId(), row.getEventType(), msg);
        }
    }
}
