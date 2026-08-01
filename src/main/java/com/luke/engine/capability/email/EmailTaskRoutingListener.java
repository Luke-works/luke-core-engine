package com.luke.engine.capability.email;

import org.finos.fluxnova.bpm.engine.delegate.DelegateTask;
import org.finos.fluxnova.bpm.engine.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies the routing decision to the review task as it is created — name, assignee, candidate
 * group and priority, all resolved at receipt by {@link EmailRoutingRuleService}.
 *
 * <p><b>Why a listener and not BPMN expressions.</b> The obvious encoding is
 * {@code camunda:assignee="${emailAssignee}"} on the user task. That couples the process
 * definition to a variable existing for every start path: an instance started without those
 * variables (a REST call, a test, an older workflow correlating into the same process) fails
 * activity creation outright rather than producing an unrouted task. Reading them here, with a
 * null check per field, degrades to "an ordinary unassigned review task" instead — which is
 * exactly the right behaviour when nobody has authored a rule.
 */
@Component("emailTaskRoutingListener")
public class EmailTaskRoutingListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(EmailTaskRoutingListener.class);

    @Override
    public void notify(DelegateTask task) {
        try {
            String name = str(task.getVariable("emailTaskName"));
            if (name != null) task.setName(name);

            String assignee = str(task.getVariable("emailAssignee"));
            if (assignee != null) task.setAssignee(assignee);

            String candidateGroup = str(task.getVariable("emailCandidateGroup"));
            if (candidateGroup != null) task.addCandidateGroup(candidateGroup);

            Object priority = task.getVariable("emailPriority");
            if (priority instanceof Number n) task.setPriority(n.intValue());
        } catch (RuntimeException e) {
            // Routing is a convenience; a task that exists unrouted beats no task at all, and
            // throwing here would roll the process start back and lose the intake entirely.
            log.warn("Could not apply email routing to task {}: {}", task.getId(), e.toString());
        }
    }

    /** Null/blank-safe string read — a blank variable means "not set", never an empty assignee. */
    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
