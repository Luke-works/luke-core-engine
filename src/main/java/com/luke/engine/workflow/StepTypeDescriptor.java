package com.luke.engine.workflow;

/**
 * A step type a capability contributes to the WORKFLOW builder — the shared,
 * data-only contract (mirror of {@code @lukeflow/workflow-core}'s
 * {@code StepTypeDescriptor}). Capabilities publish these; core aggregates them
 * into the catalog the builder fetches to render its palette.
 *
 * @param id           stable descriptor id, e.g. {@code "integration.action"}
 * @param label        human label for the palette
 * @param capability   owning capability code, e.g. {@code "EMAIL"}
 * @param kind         {@code "trigger" | "action" | "task"}
 * @param paletteGroup grouping hint for the builder
 */
public record StepTypeDescriptor(
        String id,
        String label,
        String capability,
        String kind,
        String paletteGroup) {
}
