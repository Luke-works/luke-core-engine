package com.luke.engine.capability.access;

import com.luke.engine.tenant.CapabilityOwnership;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RepositoryService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Starts the access-request approval process IN-PROCESS (no HTTP hop), tenant-scoped so the
 * tenant's own deployment is selected and the instance is tagged with the tenant.
 *
 * <p>Idempotent by business key, like {@code InternalProcessService}: the outbox drives this
 * at-least-once, and a crash between "process started" and "outbox row marked PUBLISHED" would
 * otherwise start a second approval for the same request.
 */
@Service
public class AccessApprovalProcessService {

    private static final Logger log = LoggerFactory.getLogger(AccessApprovalProcessService.class);

    /** Business keys read as {@code access-request:<id>} in Cockpit. */
    public static final String BUSINESS_KEY_PREFIX = "access-request:";

    private final RuntimeService runtimeService;
    private final IdentityService identityService;
    private final RepositoryService repositoryService;
    private final AccessRequestProcessDeployer deployer;

    @Value("${luke.access.approval-process-key:AccessRequestApprovalProcess}")
    private String processKey;

    public AccessApprovalProcessService(RuntimeService runtimeService, IdentityService identityService,
                                        RepositoryService repositoryService,
                                        AccessRequestProcessDeployer deployer) {
        this.runtimeService = runtimeService;
        this.identityService = identityService;
        this.repositoryService = repositoryService;
        this.deployer = deployer;
    }

    /**
     * Deploy the process for this tenant if it isn't already.
     *
     * <p>The deployers in this codebase only run at boot, so a tenant created afterwards has no
     * deployment until the next restart. For most capabilities that shows up as work not
     * processing; here it would mean a member's very first access request never reaching an
     * approver — a new org's first interaction. Cheap to prevent: starts are rare, so one
     * definition query per start is nothing, and duplicate filtering makes the deploy a no-op
     * whenever it is already there.
     */
    private void ensureDeployed(String tenantId) {
        try {
            long count = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processKey)
                    .tenantIdIn(tenantId)
                    .count();
            if (count == 0) {
                log.info("No {} deployed for tenant {} — deploying on demand", processKey, tenantId);
                deployer.deployFor(tenantId);
            }
        } catch (Exception e) {
            // Never block a start on the pre-check; the start itself will report the real problem.
            log.warn("Deployment pre-check failed for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    public static String businessKey(String accessRequestId) {
        return BUSINESS_KEY_PREFIX + accessRequestId;
    }

    /**
     * Start (or find) the approval process for {@code req}. The approver group is resolved HERE,
     * at start time, so the routing decision is captured as a process variable rather than being
     * re-evaluated later — an owner added mid-flight doesn't silently change who a live task
     * belongs to, and Cockpit shows exactly where it went.
     *
     * @return the process instance id
     */
    public String start(AccessRequest req) {
        String tenantId = req.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        String businessKey = businessKey(req.getId());
        String approverGroup = CapabilityOwnership.approverGroupId(
                identityService, tenantId, req.getCapabilityCode());

        Map<String, Object> vars = new HashMap<>();
        vars.put(AccessProcessVariables.ACCESS_REQUEST_ID, req.getId());
        vars.put(AccessProcessVariables.TENANT_ID, tenantId);
        vars.put(AccessProcessVariables.REQUESTER_ID, req.getUserId());
        vars.put(AccessProcessVariables.CAPABILITY_CODE, req.getCapabilityCode());
        vars.put(AccessProcessVariables.REQUESTED_LEVEL, req.getRequestedLevel());
        vars.put(AccessProcessVariables.APPROVER_GROUP, approverGroup);

        ensureDeployed(tenantId);

        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            List<ProcessInstance> existing = runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(businessKey)
                    .tenantIdIn(tenantId)
                    .list();
            if (!existing.isEmpty()) {
                String pid = existing.get(0).getProcessInstanceId();
                log.info("Idempotent start: {} (tenant {}) already running → {}", businessKey, tenantId, pid);
                return pid;
            }
            ProcessInstance pi = runtimeService.createProcessInstanceByKey(processKey)
                    .processDefinitionTenantId(tenantId)
                    .businessKey(businessKey)
                    .setVariables(vars)
                    .execute();
            log.info("Started {} for request {} (tenant {}, approvers {}) → {}",
                    processKey, req.getId(), tenantId, approverGroup, pi.getProcessInstanceId());
            return pi.getProcessInstanceId();
        } finally {
            identityService.clearAuthentication();
        }
    }
}
