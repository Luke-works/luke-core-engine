package com.luke.engine.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * How a workflow starts — a capability's inbound Trigger. Compiles to the BPMN
 * start event. {@code config} carries trigger-specific data (e.g. the {@code formId}
 * for {@code forms/form.submitted}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowTrigger(String capability, String type, Map<String, Object> config) {
}
