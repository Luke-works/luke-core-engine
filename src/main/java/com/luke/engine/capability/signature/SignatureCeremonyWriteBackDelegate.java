package com.luke.engine.capability.signature;

import org.finos.fluxnova.bpm.engine.delegate.DelegateExecution;
import org.finos.fluxnova.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records the ceremony's closure back on the {@link SignatureInstance}, IN-PROCESS: when the
 * ceremony reaches its write-back service task (after the closure message is correlated), mark the
 * Camunda binding CLOSED. The instance's business outcome (COMPLETED + sealed, or CANCELLED) was
 * already set by {@link SignatureInstanceService}; this stamps the process side. Referenced from the
 * BPMN as {@code ${signatureCeremonyWriteBackDelegate}}. Mirrors {@code FormInstanceWriteBackDelegate}.
 *
 * <p>Best-effort by design: a write-back failure must NOT roll back a logically complete process, so
 * it is caught and logged, never rethrown.
 */
@Component("signatureCeremonyWriteBackDelegate")
public class SignatureCeremonyWriteBackDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(SignatureCeremonyWriteBackDelegate.class);

    private final SignatureInstanceRepository instances;

    public SignatureCeremonyWriteBackDelegate(SignatureInstanceRepository instances) {
        this.instances = instances;
    }

    @Override
    public void execute(DelegateExecution execution) {
        try {
            Object idVar = execution.getVariable("instanceId");
            String instanceId = idVar != null ? idVar.toString() : null;
            if (instanceId == null) {
                log.warn("Ceremony write-back: no instanceId for process {}", execution.getProcessInstanceId());
                return;
            }
            instances.findById(instanceId).ifPresent(inst -> {
                inst.setProcessStatus(SignatureProcessOutbox.CLOSED);
                instances.save(inst);
            });
        } catch (Exception e) {
            log.warn("Ceremony write-back failed (process {} continues): {}",
                    execution.getProcessInstanceId(), e.getMessage());
        }
    }
}
