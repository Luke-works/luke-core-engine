package com.luke.engine.workflow;

import java.util.List;

/**
 * The outcome of compiling a {@link WorkflowDoc} to BPMN.
 *
 * @param processId the BPMN process id (derived from the workflow id + version)
 * @param bpmnXml   the serialized, schema-valid BPMN 2.0 XML, ready to deploy
 * @param warnings  non-fatal notes (e.g. a V1 feature that was only partially compiled)
 */
public record CompileResult(String processId, String bpmnXml, List<String> warnings) {
}
