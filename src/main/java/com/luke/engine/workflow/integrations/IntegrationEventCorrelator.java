package com.luke.engine.workflow.integrations;

import java.util.List;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.runtime.MessageCorrelationBuilder;
import org.cibseven.bpm.engine.runtime.MessageCorrelationResult;
import org.springframework.stereotype.Component;

/**
 * Correlates an inbound integration event to Camunda (mirrors {@code PhoneProcessService}'s
 * correlation, tenant-scoped). Uses {@code correlateAllWithResult} so one event can start a
 * workflow (message start) OR advance every workflow waiting at a matching message catch — and
 * so a no-match is a normal empty result, not an exception.
 */
@Component
public class IntegrationEventCorrelator {

    static final String EVENT_VARIABLE = "nangoEvent";

    private final RuntimeService runtimeService;
    private final IdentityService identityService;

    public IntegrationEventCorrelator(RuntimeService runtimeService, IdentityService identityService) {
        this.runtimeService = runtimeService;
        this.identityService = identityService;
    }

    /**
     * Correlate {@code messageName} for {@code tenantId}, passing the raw event as a process
     * variable. When {@code correlationKey} is set, only the instance with that business key is
     * advanced. Returns the number of executions/instances correlated.
     */
    public int correlate(String tenantId, String messageName, String correlationKey, String payloadJson) {
        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            MessageCorrelationBuilder builder = runtimeService.createMessageCorrelation(messageName)
                    .tenantId(tenantId)
                    .setVariable(EVENT_VARIABLE, payloadJson);
            if (correlationKey != null && !correlationKey.isBlank()) {
                builder = builder.processInstanceBusinessKey(correlationKey);
            }
            List<MessageCorrelationResult> results = builder.correlateAllWithResult();
            return results != null ? results.size() : 0;
        } finally {
            identityService.clearAuthentication();
        }
    }
}
