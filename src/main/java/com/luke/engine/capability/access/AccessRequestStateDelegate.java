package com.luke.engine.capability.access;

import java.time.LocalDateTime;
import org.finos.fluxnova.bpm.engine.delegate.DelegateExecution;
import org.finos.fluxnova.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Moves an access request between the non-terminal states the approval process drives:
 * {@code RETURNED} (rejected, now with the requester), {@code PENDING} (revised and resubmitted)
 * and {@code CANCELLED} (withdrawn). Referenced from the BPMN as
 * {@code ${accessRequestStateDelegate}}.
 *
 * <p>The target status arrives as the LOCAL input variable {@code targetState} (a
 * {@code camunda:inputParameter} on each service task), never as an injected field: this bean is
 * a Spring singleton, and Camunda field injection writes onto the bean instance, which concurrent
 * executions would share.
 *
 * <p>Unlike {@link AccessProvisioningDelegate} this carries no privilege consequence — the states
 * it writes are UI-visible bookkeeping, not access — but it still throws on a missing/unknown
 * state rather than silently leaving a request stuck in a status that contradicts the running
 * process.
 */
@Component("accessRequestStateDelegate")
public class AccessRequestStateDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(AccessRequestStateDelegate.class);

    private final AccessRequestRepository requests;

    public AccessRequestStateDelegate(AccessRequestRepository requests) {
        this.requests = requests;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String target = str(execution.getVariableLocal(AccessProcessVariables.TARGET_STATE));
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("No " + AccessProcessVariables.TARGET_STATE
                    + " input mapping on activity " + execution.getCurrentActivityId());
        }
        String requestId = str(execution.getVariable(AccessProcessVariables.ACCESS_REQUEST_ID));
        if (requestId == null) {
            throw new IllegalStateException("No " + AccessProcessVariables.ACCESS_REQUEST_ID
                    + " on process " + execution.getProcessInstanceId());
        }
        AccessRequest req = requests.findById(requestId).orElseThrow(
                () -> new IllegalStateException("Access request " + requestId + " no longer exists"));

        switch (target) {
            case AccessRequest.RETURNED -> {
                // Rejected: record who said no and why, and hand it back. Deliberately NOT
                // DENIED — the process is still live, waiting on the requester.
                req.setStatus(AccessRequest.RETURNED);
                req.setDecisionNote(str(execution.getVariable(AccessProcessVariables.DECISION_NOTE)));
                req.setDecidedBy(str(execution.getVariable(AccessProcessVariables.DECIDED_BY)));
                req.setDecidedAt(LocalDateTime.now());
            }
            case AccessRequest.PENDING -> {
                // Resubmitted: back with the owners, carrying whatever the requester revised
                // (the rework endpoint writes requestedLevel/note onto the process first).
                req.setStatus(AccessRequest.PENDING);
                req.setResubmitCount(req.getResubmitCount() + 1);
                String level = str(execution.getVariable(AccessProcessVariables.REQUESTED_LEVEL));
                if (level != null && CapabilityLevel.isValid(level)) {
                    req.setRequestedLevel(level);
                }
                // The decision fields describe a decision that no longer stands; the note is kept
                // as the feedback the requester is answering.
                req.setDecidedBy(null);
                req.setDecidedAt(null);
            }
            case AccessRequest.CANCELLED -> {
                req.setStatus(AccessRequest.CANCELLED);
                req.setDecidedAt(LocalDateTime.now());
            }
            default -> throw new IllegalStateException("Unsupported targetState '" + target + "'");
        }
        req.setProcessInstanceId(execution.getProcessInstanceId());
        requests.save(req);

        log.info("Access request {} → {} (process {})", requestId, target, execution.getProcessInstanceId());
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
