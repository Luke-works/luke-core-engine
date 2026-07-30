package com.luke.engine.capability.access;

import java.time.LocalDateTime;
import org.finos.fluxnova.bpm.engine.delegate.DelegateExecution;
import org.finos.fluxnova.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fulfils an approved access request: grants the capability and marks the request APPROVED.
 * Referenced from {@code AccessRequestApprovalProcess.bpmn} as {@code ${accessProvisioningDelegate}}.
 *
 * <p><b>This delegate must fail loudly.</b> The other write-back delegates in this codebase are
 * best-effort (a failure there loses a status flag), but here a swallowed exception would leave a
 * process that reports "approved" while the member holds no access — or worse, the inverse if the
 * order were reversed. So: grant first, then record the decision, and let anything that goes wrong
 * propagate. Camunda leaves an incident on the service task and the operator retries it, with the
 * request row still PENDING and honest about reality.
 *
 * <p>Grants through the same in-process path the org-admin tools use
 * ({@link CapabilityGrantController#setGrant}), so subscription checks, auditing and the
 * two-layer subscribe-then-grant behaviour are identical however access is provisioned.
 */
@Component("accessProvisioningDelegate")
public class AccessProvisioningDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(AccessProvisioningDelegate.class);

    private final AccessRequestRepository requests;
    private final CapabilityGrantController grantController;

    public AccessProvisioningDelegate(AccessRequestRepository requests,
                                      CapabilityGrantController grantController) {
        this.requests = requests;
        this.grantController = grantController;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String requestId = str(execution.getVariable(AccessProcessVariables.ACCESS_REQUEST_ID));
        if (requestId == null) {
            throw new IllegalStateException("No " + AccessProcessVariables.ACCESS_REQUEST_ID
                    + " on process " + execution.getProcessInstanceId());
        }
        AccessRequest req = requests.findById(requestId).orElseThrow(
                () -> new IllegalStateException("Access request " + requestId + " no longer exists"));

        // The approver may grant a different level than the one asked for.
        String level = str(execution.getVariable(AccessProcessVariables.GRANT_LEVEL));
        if (level == null || level.isBlank()) {
            level = req.getRequestedLevel();
        }
        if (!CapabilityLevel.isValid(level)) {
            throw new IllegalStateException("Refusing to provision invalid level '" + level
                    + "' for request " + requestId);
        }
        String decidedBy = str(execution.getVariable(AccessProcessVariables.DECIDED_BY));

        // Grant FIRST: if this throws, the request stays PENDING rather than claiming a grant
        // that never happened.
        grantController.setGrant(req.getTenantId(), req.getUserId(), req.getCapabilityCode(), decidedBy,
                new CapabilityGrantController.GrantBody(level));

        req.setStatus(AccessRequest.APPROVED);
        req.setRequestedLevel(level);
        req.setDecidedBy(decidedBy);
        req.setDecidedAt(LocalDateTime.now());
        req.setDecisionNote(str(execution.getVariable(AccessProcessVariables.DECISION_NOTE)));
        req.setProcessInstanceId(execution.getProcessInstanceId());
        requests.save(req);

        log.info("Provisioned {} {} for user {} (tenant {}) via process {}",
                req.getCapabilityCode(), level, req.getUserId(), req.getTenantId(),
                execution.getProcessInstanceId());
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
