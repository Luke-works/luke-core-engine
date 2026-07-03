package com.luke.engine.capability.phone;

import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Starts and closes the Camunda {@code PhoneCallProcess} behind a {@link PhoneCall}, IN-PROCESS
 * (no HTTP hop). A call start launches the process — it parks at the "Await Call End" receive task —
 * and the call reaching a terminal status correlates the {@code PhoneCallEnded} message to finish it.
 * Mirrors {@code SignatureCeremonyService}.
 *
 * <p>Both operations are tenant-scoped so the tenant-specific deployment is selected and the instance
 * is tagged with the tenant. Start is idempotent on the business key (the outbox drives it
 * at-least-once); a retry after a crash returns the already-running instance instead of a duplicate.
 */
@Service
public class PhoneProcessService {

    private static final Logger log = LoggerFactory.getLogger(PhoneProcessService.class);

    /** Must match the {@code <bpmn:message name="...">} in PhoneCallProcess.bpmn. */
    public static final String CALL_ENDED_MESSAGE = "PhoneCallEnded";

    private final RuntimeService runtimeService;
    private final IdentityService identityService;

    @Value("${luke.phone.call-process-key:PhoneCallProcess}")
    private String callProcessKey;

    public PhoneProcessService(RuntimeService runtimeService, IdentityService identityService) {
        this.runtimeService = runtimeService;
        this.identityService = identityService;
    }

    /** Start the process for a tenant's call; returns the new process instance id. Throws on failure. */
    public String start(String tenantId, String businessKey, Map<String, Object> variables) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            if (businessKey != null && !businessKey.isBlank()) {
                List<ProcessInstance> existing = runtimeService.createProcessInstanceQuery()
                        .processInstanceBusinessKey(businessKey)
                        .tenantIdIn(tenantId)
                        .list();
                if (!existing.isEmpty()) {
                    String pid = existing.get(0).getProcessInstanceId();
                    log.info("Idempotent phone-call start: businessKey {} (tenant {}) already running → {}",
                            businessKey, tenantId, pid);
                    return pid;
                }
            }
            ProcessInstance pi = runtimeService.createProcessInstanceByKey(callProcessKey)
                    .processDefinitionTenantId(tenantId)
                    .businessKey(businessKey)
                    .setVariables(variables != null ? variables : Map.of())
                    .execute();
            log.info("Started {} (tenant {}, businessKey {}) → {}",
                    callProcessKey, tenantId, businessKey, pi.getProcessInstanceId());
            return pi.getProcessInstanceId();
        } finally {
            identityService.clearAuthentication();
        }
    }

    /**
     * Correlate the call-ended message to the waiting process for this call, finishing it.
     * Throws {@code MismatchingMessageCorrelationException} if no execution is waiting (e.g. the
     * process is not yet started, or already closed) — the caller decides how to handle that.
     */
    public void correlateEnded(String tenantId, String businessKey, Map<String, Object> variables) {
        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            runtimeService.createMessageCorrelation(CALL_ENDED_MESSAGE)
                    .processInstanceBusinessKey(businessKey)
                    .tenantId(tenantId)
                    .setVariables(variables != null ? variables : Map.of())
                    .correlateWithResult();
            log.info("Correlated {} for businessKey {} (tenant {})", CALL_ENDED_MESSAGE, businessKey, tenantId);
        } finally {
            identityService.clearAuthentication();
        }
    }
}
