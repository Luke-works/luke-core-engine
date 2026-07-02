package com.luke.engine.workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.runtime.MessageCorrelationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers a forms lifecycle event to Camunda, tenant-scoped, in two ways:
 *
 * <ul>
 *   <li><b>Start</b> — every {@link WorkflowTriggerSubscription} matching the tenant +
 *       {@code forms.<eventType>} (and the specific form, or "any form") starts its deployed
 *       process. Uses the registry (not a BPMN message start event) so it's free of Camunda's
 *       global message-start-name uniqueness constraint.</li>
 *   <li><b>Resume</b> — a message correlation ({@code forms.<eventType>}) advances any running
 *       instance waiting at a matching message catch (a WAIT-on-event node).</li>
 * </ul>
 *
 * The form event is passed to both as the {@code formEvent} process variable (plus
 * {@code formInstanceId} / {@code formCode}). Returns the total number of instances
 * started or advanced (0 → the consumer records SKIPPED).
 */
@Component
public class FormEventCorrelator {

    private static final Logger log = LoggerFactory.getLogger(FormEventCorrelator.class);
    private static final String CAPABILITY = "forms";

    private final WorkflowTriggerSubscriptionRepository subscriptions;
    private final RuntimeService runtimeService;
    private final IdentityService identityService;

    public FormEventCorrelator(WorkflowTriggerSubscriptionRepository subscriptions,
            RuntimeService runtimeService, IdentityService identityService) {
        this.subscriptions = subscriptions;
        this.runtimeService = runtimeService;
        this.identityService = identityService;
    }

    public int correlate(String tenantId, String eventType, String formCode, String instanceId, String payloadJson) {
        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("formEvent", payloadJson);
            vars.put("formInstanceId", instanceId);
            vars.put("formCode", formCode);
            vars.put("formEventType", eventType);
            return startSubscribers(tenantId, eventType, formCode, instanceId, vars)
                    + resumeWaiters(tenantId, eventType, vars);
        } finally {
            identityService.clearAuthentication();
        }
    }

    /** Start each published workflow subscribed to this event (form-scoped, or any-form). */
    private int startSubscribers(String tenantId, String eventType, String formCode,
            String instanceId, Map<String, Object> vars) {
        int started = 0;
        for (WorkflowTriggerSubscription sub :
                subscriptions.findByTenantIdAndCapabilityAndEventType(tenantId, CAPABILITY, eventType)) {
            if (sub.getFormCode() != null && !sub.getFormCode().equals(formCode)) continue; // scoped to another form
            try {
                runtimeService.createProcessInstanceByKey(sub.getProcessId())
                        .processDefinitionTenantId(tenantId)
                        .businessKey("wf-form-" + instanceId)
                        .setVariables(vars)
                        .execute();
                started++;
            } catch (RuntimeException e) {
                // A stale subscription (process undeployed) must not block the batch or the
                // other subscribers; log and move on rather than failing the whole event.
                log.warn("Form event start skipped for subscription {} (process {}): {}",
                        sub.getId(), sub.getProcessId(), e.getMessage());
            }
        }
        return started;
    }

    /** Advance any instance waiting at a {@code forms.<eventType>} message catch. */
    private int resumeWaiters(String tenantId, String eventType, Map<String, Object> vars) {
        List<MessageCorrelationResult> results = runtimeService
                .createMessageCorrelation(CAPABILITY + "." + eventType)
                .tenantId(tenantId)
                .setVariables(vars)
                .correlateAllWithResult();
        return results != null ? results.size() : 0;
    }
}
