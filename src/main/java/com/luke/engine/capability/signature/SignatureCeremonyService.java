package com.luke.engine.capability.signature;

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
 * Starts and closes the Camunda "ceremony" process behind a {@link SignatureInstance}, IN-PROCESS
 * (no HTTP hop). The standalone signature engine had no embedded Camunda; in core the ceremony IS a
 * real process: a campaign start launches it (it parks at the "Await Signatures" receive task) and
 * the campaign reaching a terminal state correlates the {@code SignatureClosure} message to finish
 * it. Mirrors {@code com.luke.engine.form.InternalProcessService}.
 *
 * <p>Both operations are scoped to the tenant so the tenant-specific deployment is selected and the
 * instance is tagged with the tenant. Start is idempotent on the business key (the outbox drives it
 * at-least-once); a retry after a crash returns the already-running instance instead of a duplicate.
 */
@Service
public class SignatureCeremonyService {

    private static final Logger log = LoggerFactory.getLogger(SignatureCeremonyService.class);

    /** Must match the {@code <bpmn:message name="...">} in SignatureCeremonyProcess.bpmn. */
    public static final String CLOSURE_MESSAGE = "SignatureClosure";

    private final RuntimeService runtimeService;
    private final IdentityService identityService;

    @Value("${luke.signatures.ceremony-process-key:SignatureCeremonyProcess}")
    private String ceremonyProcessKey;

    public SignatureCeremonyService(RuntimeService runtimeService, IdentityService identityService) {
        this.runtimeService = runtimeService;
        this.identityService = identityService;
    }

    /** Start the ceremony for a tenant; returns the new process instance id. Throws on failure. */
    public String start(String tenantId, String businessKey, Map<String, Object> variables) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            // Idempotency: if one with this businessKey is already running for the tenant, return it.
            if (businessKey != null && !businessKey.isBlank()) {
                List<ProcessInstance> existing = runtimeService.createProcessInstanceQuery()
                        .processInstanceBusinessKey(businessKey)
                        .tenantIdIn(tenantId)
                        .list();
                if (!existing.isEmpty()) {
                    String pid = existing.get(0).getProcessInstanceId();
                    log.info("Idempotent ceremony start: businessKey {} (tenant {}) already running → {}",
                            businessKey, tenantId, pid);
                    return pid;
                }
            }
            ProcessInstance pi = runtimeService.createProcessInstanceByKey(ceremonyProcessKey)
                    .processDefinitionTenantId(tenantId)
                    .businessKey(businessKey)
                    .setVariables(variables != null ? variables : Map.of())
                    .execute();
            log.info("Started {} (tenant {}, businessKey {}) → {}",
                    ceremonyProcessKey, tenantId, businessKey, pi.getProcessInstanceId());
            return pi.getProcessInstanceId();
        } finally {
            identityService.clearAuthentication();
        }
    }

    /**
     * Correlate the closure message to the waiting ceremony for this instance, finishing it.
     * Throws {@code MismatchingMessageCorrelationException} if no execution is waiting (e.g. the
     * process is not yet started, or already closed) — the caller decides how to handle that.
     */
    public void correlateClosure(String tenantId, String businessKey, Map<String, Object> variables) {
        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            runtimeService.createMessageCorrelation(CLOSURE_MESSAGE)
                    .processInstanceBusinessKey(businessKey)
                    .tenantId(tenantId)
                    .setVariables(variables != null ? variables : Map.of())
                    .correlateWithResult();
            log.info("Correlated {} for businessKey {} (tenant {})", CLOSURE_MESSAGE, businessKey, tenantId);
        } finally {
            identityService.clearAuthentication();
        }
    }
}
