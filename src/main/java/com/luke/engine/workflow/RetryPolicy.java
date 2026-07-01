package com.luke.engine.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A per-node retry policy. Compiles (WF-11) to a Camunda failed-job retry time
 * cycle, e.g. {@code maxAttempts=5, backoff=exponential, initialDelay="30s"}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetryPolicy(Integer maxAttempts, String backoff, String initialDelay) {
}
