package com.luke.engine.workflow;

import java.util.List;

/**
 * One typed input a {@link StepTypeDescriptor} declares — a flat, purpose-built field
 * the WORKFLOW builder auto-renders into a labelled control (mirror of
 * {@code @lukeflow/workflow-core}'s {@code StepFieldDescriptor}). Additive: keys a node
 * sets that aren't declared here still fall back to the builder's generic key/value editor.
 *
 * @param key         the node-input key this field writes, e.g. {@code "to"}
 * @param label       human label for the control
 * @param type        control kind: text | textarea | number | boolean | select | expression
 * @param required    whether the field is required (advisory in the builder)
 * @param placeholder optional placeholder text
 * @param help        optional helper text under the control
 * @param options     options for {@code type = "select"} (else null)
 */
public record StepFieldDescriptor(
        String key,
        String label,
        String type,
        boolean required,
        String placeholder,
        String help,
        List<Option> options) {

    /** One option for a {@code select} field. */
    public record Option(String value, String label) {}

    /** A required single-line text field. */
    public static StepFieldDescriptor req(String key, String label) {
        return new StepFieldDescriptor(key, label, "text", true, null, null, null);
    }

    /** An optional single-line text field. */
    public static StepFieldDescriptor text(String key, String label) {
        return new StepFieldDescriptor(key, label, "text", false, null, null, null);
    }

    /** An optional multi-line text field. */
    public static StepFieldDescriptor area(String key, String label) {
        return new StepFieldDescriptor(key, label, "textarea", false, null, null, null);
    }

    /** An optional numeric field. */
    public static StepFieldDescriptor num(String key, String label) {
        return new StepFieldDescriptor(key, label, "number", false, null, null, null);
    }
}
