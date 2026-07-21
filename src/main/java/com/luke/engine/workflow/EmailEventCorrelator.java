package com.luke.engine.workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.runtime.MessageCorrelationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers an inbound-email event to Camunda, tenant-scoped — the EMAIL analogue of
 * {@link FormEventCorrelator}. An email arriving at an INBOUND box (via the public inbound
 * webhook) fires here with {@code eventType = "inbound"}:
 *
 * <ul>
 *   <li><b>Start</b> — every {@link WorkflowTriggerSubscription} matching the tenant +
 *       {@code email.inbound} starts its deployed process. The subscription's generic
 *       resource-scope field ({@code formCode}) is reused as the box <em>address</em>: a
 *       null scope means "any inbound box", else it must equal the recipient box.</li>
 *   <li><b>Resume</b> — a message correlation ({@code email.inbound}) advances any running
 *       instance waiting at a matching message catch (a WAIT-on-inbound-email node).</li>
 * </ul>
 *
 * The message is passed as process variables ({@code emailEvent} JSON, {@code emailMessageId},
 * {@code emailBox}, {@code emailFrom}, {@code emailSubject}). Returns the number of instances
 * started or advanced (0 → the caller records SKIPPED).
 */
@Component
public class EmailEventCorrelator {

    private static final Logger log = LoggerFactory.getLogger(EmailEventCorrelator.class);
    private static final String CAPABILITY = "email";

    private final WorkflowTriggerSubscriptionRepository subscriptions;
    private final RuntimeService runtimeService;
    private final IdentityService identityService;

    public EmailEventCorrelator(WorkflowTriggerSubscriptionRepository subscriptions,
            RuntimeService runtimeService, IdentityService identityService) {
        this.subscriptions = subscriptions;
        this.runtimeService = runtimeService;
        this.identityService = identityService;
    }

    /**
     * @param boxAddress the INBOUND box the mail arrived at (used to scope subscriptions)
     * @param messageId  the stored inbound {@code EmailMessage} id
     * @param from       the sender address
     * @param subject    the subject
     * @param payloadJson the raw inbound event JSON (Postmark body) as a process variable
     * @return instances started + advanced
     */
    public int correlate(String tenantId, String boxAddress, String messageId,
            String from, String subject, String payloadJson) {
        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("emailEvent", payloadJson);
            vars.put("emailMessageId", messageId);
            vars.put("emailBox", boxAddress);
            vars.put("emailFrom", from);
            vars.put("emailSubject", subject);
            vars.put("emailEventType", "inbound");
            return startSubscribers(tenantId, boxAddress, messageId, vars)
                    + resumeWaiters(tenantId, vars);
        } finally {
            identityService.clearAuthentication();
        }
    }

    /** Start each published workflow subscribed to {@code email.inbound} (box-scoped, or any-box). */
    private int startSubscribers(String tenantId, String boxAddress, String messageId, Map<String, Object> vars) {
        int started = 0;
        for (WorkflowTriggerSubscription sub :
                subscriptions.findByTenantIdAndCapabilityAndEventType(tenantId, CAPABILITY, "inbound")) {
            // formCode is reused as the box address scope for email subscriptions.
            if (sub.getFormCode() != null && !sub.getFormCode().equals(boxAddress)) continue;
            try {
                runtimeService.createProcessInstanceByKey(sub.getProcessId())
                        .processDefinitionTenantId(tenantId)
                        .businessKey("wf-email-" + messageId)
                        .setVariables(vars)
                        .execute();
                started++;
            } catch (RuntimeException e) {
                log.warn("Inbound-email start skipped for subscription {} (process {}): {}",
                        sub.getId(), sub.getProcessId(), e.getMessage());
            }
        }
        return started;
    }

    /** Advance any instance waiting at an {@code email.inbound} message catch. */
    private int resumeWaiters(String tenantId, Map<String, Object> vars) {
        List<MessageCorrelationResult> results = runtimeService
                .createMessageCorrelation(CAPABILITY + ".inbound")
                .tenantId(tenantId)
                .setVariables(vars)
                .correlateAllWithResult();
        return results != null ? results.size() : 0;
    }
}
