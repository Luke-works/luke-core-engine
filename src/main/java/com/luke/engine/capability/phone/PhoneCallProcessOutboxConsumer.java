package com.luke.engine.capability.phone;

import java.util.HashMap;
import java.util.Map;
import org.cibseven.bpm.engine.MismatchingMessageCorrelationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the {@code PhoneCallProcess} from the transactional outbox (mirrors
 * {@code SignatureProcessOutboxConsumer}). Two phases, one poller:
 * <ol>
 *   <li><b>QUEUED → STARTED</b>: a call was placed/observed — start the process in-process
 *       (it parks at "Await Call End"), stamp the {@code processInstanceId} back on the call.</li>
 *   <li><b>STARTED → CLOSED</b>: once the call reaches a terminal status (ENDED/FAILED),
 *       correlate the {@code PhoneCallEnded} message to finish the waiting process.</li>
 * </ol>
 * Splitting start and closure across poll cycles sidesteps the start-vs-closure race: closure is
 * only attempted for rows already STARTED, by which point the process is parked at the receive task.
 *
 * <p>HA note: with &gt;1 engine node, run the poller on exactly ONE node — set
 * {@code LUKE_PHONE_OUTBOX_ENABLED=false} on the others. The unique businessKey + idempotent start
 * are a backstop, but a single poller avoids two nodes racing the same row.
 */
@Component
public class PhoneCallProcessOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(PhoneCallProcessOutboxConsumer.class);

    private final PhoneCallProcessOutboxRepository outbox;
    private final PhoneCallRepository calls;
    private final PhoneProcessService process;

    @Value("${luke.phone.outbox-enabled:true}")
    private boolean enabled;

    public PhoneCallProcessOutboxConsumer(PhoneCallProcessOutboxRepository outbox,
                                          PhoneCallRepository calls,
                                          PhoneProcessService process) {
        this.outbox = outbox;
        this.calls = calls;
        this.process = process;
    }

    @Scheduled(fixedDelayString = "${luke.phone.outbox-poll-ms:2000}")
    public void drain() {
        if (!enabled) return;
        for (PhoneCallProcessOutbox row : outbox.findByStateOrderByCreatedAtAsc(PhoneCallProcessOutbox.QUEUED)) {
            start(row);
        }
        for (PhoneCallProcessOutbox row : outbox.findByStateOrderByCreatedAtAsc(PhoneCallProcessOutbox.STARTED)) {
            maybeClose(row);
        }
    }

    /** Phase 1: launch the process for a freshly-created call. */
    void start(PhoneCallProcessOutbox row) {
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("callId", row.getCallId());
            vars.put("direction", row.getDirection());
            vars.put("tenantId", row.getTenantId());
            String pid = process.start(row.getTenantId(), row.getBusinessKey(), vars);

            row.setState(PhoneCallProcessOutbox.STARTED);
            row.setProcessInstanceId(pid);
            row.setErrorMessage(null);
            outbox.save(row);
            calls.findById(row.getCallId()).ifPresent(call -> {
                call.setProcessInstanceId(pid);
                call.setProcessStatus(PhoneCallProcessOutbox.STARTED);
                calls.save(call);
            });
        } catch (Exception e) {
            fail(row, e);
        }
    }

    /** Phase 2: if the call has ended, finish the waiting process. */
    void maybeClose(PhoneCallProcessOutbox row) {
        PhoneCall call = calls.findById(row.getCallId()).orElse(null);
        if (call == null) {
            // The call is gone (e.g. purged) — nothing left to close.
            row.setState(PhoneCallProcessOutbox.CLOSED);
            outbox.save(row);
            return;
        }
        if (!PhoneCallStatus.isTerminal(call.getStatus())) {
            return; // still in flight — leave STARTED, check again next poll
        }
        boolean writeBackRan;
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("callStatus", call.getStatus());
            if (call.getEndedReason() != null) vars.put("endedReason", call.getEndedReason());
            // Correlation runs the write-back delegate, which stamps the call processStatus=CLOSED
            // itself — so we must NOT re-save our now-stale `call` after.
            process.correlateEnded(row.getTenantId(), row.getBusinessKey(), vars);
            writeBackRan = true;
        } catch (MismatchingMessageCorrelationException e) {
            // No waiting execution (process already gone / not parked). The call is already terminal,
            // so treat the binding as closed rather than retrying forever.
            log.warn("Call-ended correlation for {} matched no waiting process — marking CLOSED: {}",
                    row.getBusinessKey(), e.getMessage());
            writeBackRan = false;
        } catch (Exception e) {
            fail(row, e); // transient (e.g. DB) — keep STARTED and retry next poll
            return;
        }
        row.setState(PhoneCallProcessOutbox.CLOSED);
        row.setErrorMessage(null);
        outbox.save(row);
        if (!writeBackRan) {
            calls.findById(row.getCallId()).ifPresent(c -> {
                c.setProcessStatus(PhoneCallProcessOutbox.CLOSED);
                calls.save(c);
            });
        }
    }

    private void fail(PhoneCallProcessOutbox row, Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        row.setErrorMessage(msg);
        row.setRetryCount(row.getRetryCount() + 1);
        outbox.save(row);
        log.warn("Phone outbox step failed for call {} (tenant {}, businessKey {}): {}",
                row.getCallId(), row.getTenantId(), row.getBusinessKey(), msg);
    }
}
