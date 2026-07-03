package com.luke.engine.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Error handling for an action/task node — compiles to a BPMN error boundary event
 * routing to {@code fallback} when retries are exhausted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErrorPolicy(RetryPolicy retry, String fallback, Boolean deadLetter) {
}
