package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes orchestration output back to the form data store, IN-PROCESS: when the
 * intake process reaches its write-back service task, mark the originating form
 * instance PROCESSED (SUBMITTED → PROCESSED). Referenced from the BPMN as
 * {@code ${formInstanceWriteBackDelegate}}.
 *
 * <p>Best-effort by design: a write-back failure must NOT roll back a logically
 * complete process, so it is caught and logged, never rethrown.
 */
@Component("formInstanceWriteBackDelegate")
public class FormInstanceWriteBackDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(FormInstanceWriteBackDelegate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FormInstanceRepository instances;

    public FormInstanceWriteBackDelegate(FormInstanceRepository instances) {
        this.instances = instances;
    }

    @Override
    public void execute(DelegateExecution execution) {
        try {
            String instanceId = instanceId(execution.getVariable("formMetaData"));
            if (instanceId == null) {
                log.warn("Write-back: no instanceId in formMetaData for process {}", execution.getProcessInstanceId());
                return;
            }
            instances.findById(instanceId).ifPresent(inst -> {
                if (FormInstanceStates.canTransition(inst.getState(), FormInstanceStates.PROCESSED)) {
                    inst.setState(FormInstanceStates.PROCESSED);
                }
                Map<String, Object> ctx = new HashMap<>(inst.getContext() != null ? inst.getContext() : Map.of());
                ctx.put("processInstanceId", execution.getProcessInstanceId());
                ctx.put("processedAt", System.currentTimeMillis());
                inst.setContext(ctx);
                instances.save(inst);
            });
        } catch (Exception e) {
            log.warn("Write-back failed (process {} continues): {}",
                    execution.getProcessInstanceId(), e.getMessage());
        }
    }

    /** formMetaData is a Spin JSON object (or a JSON string) — both render JSON via toString(). */
    private static String instanceId(Object formMetaData) {
        if (formMetaData == null) return null;
        try {
            JsonNode n = MAPPER.readTree(formMetaData.toString());
            return n.hasNonNull("instanceId") ? n.get("instanceId").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
