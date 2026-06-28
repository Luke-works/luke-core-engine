package com.luke.engine.capability.signature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.MismatchingMessageCorrelationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the signature-ceremony Camunda process from the transactional outbox (mirrors the forms
 * {@code FormSubmissionOutboxConsumer}). Two phases, one poller:
 * <ol>
 *   <li><b>QUEUED → STARTED</b>: a campaign was launched — start the ceremony process in-process
 *       (it parks at "Await Signatures"), stamp the {@code processInstanceId} back on the instance.</li>
 *   <li><b>STARTED → CLOSED</b>: once the instance reaches a terminal state (COMPLETED/CANCELLED/…),
 *       correlate the {@code SignatureClosure} message to finish the waiting process.</li>
 * </ol>
 * Splitting start and closure across poll cycles sidesteps the start-vs-closure race: closure is
 * only attempted for rows already STARTED, by which point the process is parked at the receive task.
 *
 * <p>HA note: with &gt;1 engine node, run the poller on exactly ONE node — set
 * {@code LUKE_SIGNATURES_OUTBOX_ENABLED=false} on the others. The unique businessKey + idempotent
 * start are a backstop, but a single poller avoids two nodes racing the same row.
 */
@Component
public class SignatureProcessOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(SignatureProcessOutboxConsumer.class);

    private final SignatureProcessOutboxRepository outbox;
    private final SignatureInstanceRepository instances;
    private final SignatureCeremonyService ceremony;

    @Value("${luke.signatures.outbox-enabled:true}")
    private boolean enabled;

    public SignatureProcessOutboxConsumer(SignatureProcessOutboxRepository outbox,
                                          SignatureInstanceRepository instances,
                                          SignatureCeremonyService ceremony) {
        this.outbox = outbox;
        this.instances = instances;
        this.ceremony = ceremony;
    }

    @Scheduled(fixedDelayString = "${luke.signatures.outbox-poll-ms:2000}")
    public void drain() {
        if (!enabled) return;
        for (SignatureProcessOutbox row : outbox.findByStateOrderByCreatedAtAsc(SignatureProcessOutbox.QUEUED)) {
            start(row);
        }
        for (SignatureProcessOutbox row : outbox.findByStateOrderByCreatedAtAsc(SignatureProcessOutbox.STARTED)) {
            maybeClose(row);
        }
    }

    /** Phase 1: launch the ceremony for a freshly-queued campaign. */
    void start(SignatureProcessOutbox row) {
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("instanceId", row.getInstanceId());
            vars.put("definitionCode", row.getDefinitionCode());
            vars.put("tenantId", row.getTenantId());
            String pid = ceremony.start(row.getTenantId(), row.getBusinessKey(), vars);

            row.setState(SignatureProcessOutbox.STARTED);
            row.setProcessInstanceId(pid);
            row.setErrorMessage(null);
            outbox.save(row);
            instances.findById(row.getInstanceId()).ifPresent(inst -> {
                inst.setProcessInstanceId(pid);
                inst.setProcessStatus(SignatureProcessOutbox.STARTED);
                instances.save(inst);
            });
        } catch (Exception e) {
            fail(row, e);
        }
    }

    /** Phase 2: if the campaign has closed, finish the waiting ceremony. */
    void maybeClose(SignatureProcessOutbox row) {
        SignatureInstance inst = instances.findById(row.getInstanceId()).orElse(null);
        if (inst == null) {
            // The instance is gone (e.g. purged) — nothing left to close.
            row.setState(SignatureProcessOutbox.CLOSED);
            outbox.save(row);
            return;
        }
        if (!SignatureInstanceStates.isTerminal(inst.getState())) {
            return; // still in flight — leave STARTED, check again next poll
        }
        boolean writeBackRan;
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("closureState", inst.getState());
            if (inst.getSealStatus() != null) vars.put("sealStatus", inst.getSealStatus());
            // Correlation runs the ceremony's write-back delegate, which stamps the instance
            // processStatus=CLOSED itself — so we must NOT re-save our now-stale `inst` after.
            ceremony.correlateClosure(row.getTenantId(), row.getBusinessKey(), vars);
            writeBackRan = true;
        } catch (MismatchingMessageCorrelationException e) {
            // No waiting execution (process already gone / not parked). The instance is already
            // terminal, so treat the binding as closed rather than retrying forever.
            log.warn("Closure correlation for {} matched no waiting ceremony — marking CLOSED: {}",
                    row.getBusinessKey(), e.getMessage());
            writeBackRan = false;
        } catch (Exception e) {
            fail(row, e); // transient (e.g. DB) — keep STARTED and retry next poll
            return;
        }
        row.setState(SignatureProcessOutbox.CLOSED);
        row.setErrorMessage(null);
        outbox.save(row);
        // Backstop only when the delegate never ran (re-load to avoid an optimistic-lock clash).
        if (!writeBackRan) {
            instances.findById(row.getInstanceId()).ifPresent(i -> {
                i.setProcessStatus(SignatureProcessOutbox.CLOSED);
                instances.save(i);
            });
        }
    }

    private void fail(SignatureProcessOutbox row, Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        row.setErrorMessage(msg);
        row.setRetryCount(row.getRetryCount() + 1);
        outbox.save(row); // stays in its current state for the next poll to retry
        log.warn("Signature outbox step failed for instance {} (tenant {}, businessKey {}): {}",
                row.getInstanceId(), row.getTenantId(), row.getBusinessKey(), msg);
    }
}
