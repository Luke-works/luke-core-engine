package com.luke.engine.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The stored workflow document — the friendly JSON DSL authored by the builder
 * (mirror of {@code @lukeflow/workflow-core}'s {@code WorkflowDoc}). It is NOT
 * executed here: {@link WorkflowCompiler} translates it to BPMN, which Camunda /
 * CIBSeven runs. The builder validates for UX; the server re-validates and
 * compiles for truth.
 *
 * <p>The shape is deliberately tolerant (unknown fields ignored) so a newer
 * builder can add authoring hints without breaking older engines.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDoc(
        String id,
        Integer version,
        String name,
        WorkflowTrigger trigger,
        List<WorkflowNode> nodes,
        /** Explicit start node id; defaults to {@code nodes[0]} when absent. */
        String start) {
}
