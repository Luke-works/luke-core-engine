package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Writes forms lifecycle events onto the transactional outbox ({@link FormEventOutbox}).
 * Deliberately annotation-free on transactions: it participates in the CALLER's transaction
 * so the event row commits atomically with the state change (e.g. inside
 * {@link FormSubmissionService#submit}). When a caller isn't transactional (a controller
 * transition), it's a best-effort save right after the state persists.
 *
 * <p>The payload snapshots the instance's data plus identity fields, so a workflow started
 * by the event has the submission available as the {@code formEvent} process variable.
 */
@Component
public class FormEventPublisher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FormEventOutboxRepository outbox;

    public FormEventPublisher(FormEventOutboxRepository outbox) {
        this.outbox = outbox;
    }

    /** Enqueue {@code eventType} (a lower-cased instance state) for {@code inst}. No-op on a blank type. */
    public void emit(FormInstance inst, String eventType) {
        if (inst == null || !StringUtils.hasText(eventType)) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", inst.getId());
        payload.put("formCode", inst.getDefinitionCode());
        payload.put("tenantId", inst.getTenantId());
        payload.put("version", inst.getVersion());
        payload.put("event", eventType);
        payload.put("data", inst.getData() != null ? inst.getData() : Map.of());
        outbox.save(new FormEventOutbox(
                inst.getTenantId(), eventType, inst.getDefinitionCode(), inst.getId(), json(payload)));
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o != null ? o : Map.of());
        } catch (Exception e) {
            return "{}";
        }
    }
}
