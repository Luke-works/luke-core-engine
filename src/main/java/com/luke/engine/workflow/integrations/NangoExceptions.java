package com.luke.engine.workflow.integrations;

/**
 * The classification of a Nango action failure, which drives how the
 * {@link ConnectorExecutor} handles it:
 *
 * <ul>
 *   <li>{@link NangoAuthException} — the connection's tokens are bad/expired → mark
 *       NEEDS_RECONNECT + raise a BPMN error (route to the workflow's reconnect path).</li>
 *   <li>{@link NangoRateLimitException} / {@link NangoServerException} — transient → let
 *       Camunda retry the job with backoff.</li>
 *   <li>{@link NangoActionException} — a business/4xx failure → raise a BPMN error (route to
 *       the fallback), don't retry.</li>
 * </ul>
 */
public final class NangoExceptions {
    private NangoExceptions() {}
}
