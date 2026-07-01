package com.luke.engine.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One arm of a branch: take {@code next} when {@code expr} is truthy. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BranchCondition(String expr, String next) {
}
