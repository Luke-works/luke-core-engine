package com.luke.engine.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * A single node in the workflow graph. Mirrors the loose TS union: one record with
 * nullable fields discriminated by {@link #kind()} ({@code action | task | branch |
 * parallel | wait}). Fields not relevant to a given kind are simply {@code null}.
 *
 * <p>Node ids are preserved verbatim as the BPMN flow-element ids, so at runtime a
 * Camunda {@code activityId} maps straight back to the authoring node (and its
 * capability/action) without embedding capability metadata in the BPMN.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowNode(
        String id,
        String kind,
        String name,

        // action / task
        String capability,
        String action,
        String provider,
        String connection,
        Map<String, Object> input,
        String output,
        String task,
        String assignee,
        ErrorPolicy onError,
        String next,

        // branch
        List<BranchCondition> conditions,
        @JsonProperty("else") String elseTarget,

        // parallel
        List<String> branches,
        String join,

        // wait
        String mode,
        String duration,
        WaitEvent event) {
}
