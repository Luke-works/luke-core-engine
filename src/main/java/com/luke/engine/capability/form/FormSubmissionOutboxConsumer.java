package com.luke.engine.capability.form;

import com.luke.engine.form.InternalProcessService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the form-submission outbox: starts the Camunda intake process in-process
 * for each QUEUED row and flips it to PUBLISHED/FAILED, recording the outcome on
 * the form instance (so the UI tracker still shows started + id / failed + error).
 *
 * <p>HA note: with &gt;1 engine node, run the poller on exactly ONE node — set
 * {@code LUKE_FORMS_OUTBOX_ENABLED=false} on the others. The unique businessKey is
 * a backstop, but a single poller avoids two nodes racing the same row.
 */
@Component
public class FormSubmissionOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(FormSubmissionOutboxConsumer.class);

    private final FormSubmissionOutboxRepository outbox;
    private final FormInstanceRepository instances;
    private final InternalProcessService processService;

    @Value("${luke.forms.outbox-enabled:true}")
    private boolean enabled;

    public FormSubmissionOutboxConsumer(FormSubmissionOutboxRepository outbox,
                                        FormInstanceRepository instances,
                                        InternalProcessService processService) {
        this.outbox = outbox;
        this.instances = instances;
        this.processService = processService;
    }

    @Scheduled(fixedDelayString = "${luke.forms.outbox-poll-ms:2000}")
    public void drain() {
        if (!enabled) return;
        List<FormSubmissionOutbox> queued = outbox.findByStatusOrderByCreatedAtAsc("QUEUED");
        for (FormSubmissionOutbox row : queued) {
            process(row);
        }
    }

    void process(FormSubmissionOutbox row) {
        try {
            Map<String, Object> vars = new HashMap<>();
            if (row.getFormDataJson() != null) vars.put("formData", row.getFormDataJson());
            if (row.getFormMetaJson() != null) vars.put("formMetaData", row.getFormMetaJson());

            String pid = processService.start(row.getTenantId(), row.getBusinessKey(), vars);

            row.setStatus("PUBLISHED");
            row.setProcessInstanceId(pid);
            row.setPublishedAt(Instant.now());
            row.setErrorMessage(null);
            outbox.save(row);
            recordOutcome(row.getFormInstanceId(), "STARTED", pid, null);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            row.setStatus("FAILED");
            row.setErrorMessage(msg);
            outbox.save(row);
            recordOutcome(row.getFormInstanceId(), "FAILED", null, msg);
            log.warn("Outbox start failed for instance {} (tenant {}): {}",
                    row.getFormInstanceId(), row.getTenantId(), msg);
        }
    }

    private void recordOutcome(String instanceId, String status, String pid, String error) {
        instances.findById(instanceId).ifPresent(inst -> {
            Map<String, Object> ctx = new HashMap<>(inst.getContext() != null ? inst.getContext() : Map.of());
            ctx.put("processStartStatus", status);
            ctx.put("processStartAt", System.currentTimeMillis());
            if (pid != null) ctx.put("processInstanceId", pid);
            if (error != null) ctx.put("processStartError", error); else ctx.remove("processStartError");
            inst.setContext(ctx);
            instances.save(inst);
        });
    }
}
