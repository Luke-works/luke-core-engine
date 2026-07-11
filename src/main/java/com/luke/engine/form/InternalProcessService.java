package com.luke.engine.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.runtime.ProcessInstance;
import org.finos.fluxnova.spin.plugin.variable.SpinValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Starts the generic intake process IN-PROCESS (no HTTP hop). Extracted from
 * {@link InternalProcessController} so both the retained internal endpoint and the
 * form-submission outbox consumer share one implementation.
 *
 * <p>{@code formData}/{@code formMetaData} (JSON strings or objects) are stored as
 * Spin JSON variables, navigable as {@code ${formData.prop('email')}}. The engine
 * is scoped to the tenant so the tenant-specific definition is selected and the
 * instance is tagged with the tenant.
 */
@Service
public class InternalProcessService {

    private static final Logger log = LoggerFactory.getLogger(InternalProcessService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuntimeService runtimeService;
    private final IdentityService identityService;

    @Value("${luke.forms.intake-process-key:FormSubmissionIntakeProcess}")
    private String intakeProcessKey;

    public InternalProcessService(RuntimeService runtimeService, IdentityService identityService) {
        this.runtimeService = runtimeService;
        this.identityService = identityService;
    }

    /** Start the intake process for a tenant; returns the new process instance id. Throws on failure. */
    public String start(String tenantId, String businessKey, Map<String, Object> variables) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        Map<String, Object> vars = variables != null ? new HashMap<>(variables) : new HashMap<>();
        for (String varKey : new String[] {"formData", "formMetaData"}) {
            Object v = vars.get(varKey);
            if (v == null) continue;
            String json = v instanceof String s ? s : null;
            if (json == null) {
                try { json = MAPPER.writeValueAsString(v); } catch (Exception ignored) { /* leave null */ }
            }
            if (json != null && !json.isBlank()) {
                try {
                    vars.put(varKey, SpinValues.jsonValue(json).create());
                } catch (Exception e) {
                    vars.put(varKey, json); // fall back to a plain string if not valid JSON
                }
            }
        }

        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            // Idempotency (#31): the outbox drives this at-least-once and a retry after a
            // crash (process started, but the outbox row not yet marked PUBLISHED) would
            // otherwise start a SECOND process. If one with this businessKey is already
            // running for the tenant, return it instead of starting a duplicate.
            if (businessKey != null && !businessKey.isBlank()) {
                List<ProcessInstance> existing = runtimeService.createProcessInstanceQuery()
                        .processInstanceBusinessKey(businessKey)
                        .tenantIdIn(tenantId)
                        .list();
                if (!existing.isEmpty()) {
                    String pid = existing.get(0).getProcessInstanceId();
                    log.info("Idempotent start: businessKey {} (tenant {}) already running → {}",
                            businessKey, tenantId, pid);
                    return pid;
                }
            }
            ProcessInstance pi = runtimeService.createProcessInstanceByKey(intakeProcessKey)
                    .processDefinitionTenantId(tenantId)
                    .businessKey(businessKey)
                    .setVariables(vars)
                    .execute();
            log.info("Started {} (tenant {}, businessKey {}) → {}",
                    intakeProcessKey, tenantId, businessKey, pi.getProcessInstanceId());
            return pi.getProcessInstanceId();
        } finally {
            identityService.clearAuthentication();
        }
    }
}
