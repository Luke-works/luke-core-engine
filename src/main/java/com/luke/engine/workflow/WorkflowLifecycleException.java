package com.luke.engine.workflow;

/**
 * A design-time lifecycle violation (definition not found, publishing an unsigned
 * version, signing off a non-compiling snapshot, …). Mapped to HTTP 400 by
 * {@link WorkflowDefinitionController}.
 */
public class WorkflowLifecycleException extends RuntimeException {
    public WorkflowLifecycleException(String message) {
        super(message);
    }
}
