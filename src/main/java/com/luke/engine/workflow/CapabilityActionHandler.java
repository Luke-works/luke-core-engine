package com.luke.engine.workflow;

import java.util.Map;

/**
 * The outbound-rail SPI: a capability contributes one handler that executes its
 * {@code action} workflow steps. The {@link com.luke.engine.workflow.integrations.ConnectorExecutor}
 * dispatches every non-integrations action node to the handler registered for that
 * capability (integrations is handled inline via Nango). A capability with no handler
 * completes its action as a no-op (so a workflow still runs while handlers are added
 * incrementally).
 *
 * <p>Handlers are plain Spring beans; the executor collects them by {@link #capability()}.
 */
public interface CapabilityActionHandler {

    /** The capability code this handler serves, e.g. {@code "email"} (matched case-insensitively). */
    String capability();

    /**
     * Execute the node's action. {@code variables} is a read-only snapshot of the process
     * variables (for resolving {@code {{placeholder}}} inputs). Return a value to store under
     * {@code node.output()}, or {@code null}. Throw {@link CapabilityActionException} for a
     * business failure that should route to the workflow's fallback.
     */
    Object execute(String tenantId, WorkflowNode node, Map<String, Object> variables);
}
