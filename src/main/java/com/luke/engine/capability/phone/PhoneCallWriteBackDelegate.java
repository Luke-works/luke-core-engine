package com.luke.engine.capability.phone;

import org.finos.fluxnova.bpm.engine.delegate.DelegateExecution;
import org.finos.fluxnova.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records the call's closure back on the {@link PhoneCall}, IN-PROCESS: when the process reaches its
 * write-back service task (after the {@code PhoneCallEnded} message is correlated), mark the Camunda
 * binding CLOSED. The call's business outcome (ENDED/FAILED + transcript/recording/cost) was already
 * set from the webhook; this stamps the process side. Referenced from the BPMN as
 * {@code ${phoneCallWriteBackDelegate}}. Mirrors {@code SignatureCeremonyWriteBackDelegate}.
 *
 * <p>Best-effort by design: a write-back failure must NOT roll back a logically complete process, so
 * it is caught and logged, never rethrown.
 */
@Component("phoneCallWriteBackDelegate")
public class PhoneCallWriteBackDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(PhoneCallWriteBackDelegate.class);

    private final PhoneCallRepository calls;

    public PhoneCallWriteBackDelegate(PhoneCallRepository calls) {
        this.calls = calls;
    }

    @Override
    public void execute(DelegateExecution execution) {
        try {
            Object idVar = execution.getVariable("callId");
            String callId = idVar != null ? idVar.toString() : null;
            if (callId == null) {
                log.warn("Phone write-back: no callId for process {}", execution.getProcessInstanceId());
                return;
            }
            calls.findById(callId).ifPresent(call -> {
                call.setProcessStatus(PhoneCallProcessOutbox.CLOSED);
                calls.save(call);
            });
        } catch (Exception e) {
            log.warn("Phone write-back failed (process {} continues): {}",
                    execution.getProcessInstanceId(), e.getMessage());
        }
    }
}
