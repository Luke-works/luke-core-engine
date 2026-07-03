package com.luke.engine.workflow;

/**
 * A business failure inside a {@link CapabilityActionHandler} — the executor turns it
 * into a BPMN {@code action-error} so the workflow routes to its fallback (no retry).
 */
public class CapabilityActionException extends RuntimeException {
    public CapabilityActionException(String message) {
        super(message);
    }
}
