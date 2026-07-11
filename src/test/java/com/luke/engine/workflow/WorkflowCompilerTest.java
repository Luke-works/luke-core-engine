package com.luke.engine.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.finos.fluxnova.bpm.model.bpmn.Bpmn;
import org.finos.fluxnova.bpm.model.bpmn.BpmnModelInstance;
import org.finos.fluxnova.bpm.model.bpmn.instance.BoundaryEvent;
import org.finos.fluxnova.bpm.model.bpmn.instance.EndEvent;
import org.finos.fluxnova.bpm.model.bpmn.instance.ExclusiveGateway;
import org.finos.fluxnova.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.finos.fluxnova.bpm.model.bpmn.instance.SequenceFlow;
import org.finos.fluxnova.bpm.model.bpmn.instance.ServiceTask;
import org.finos.fluxnova.bpm.model.bpmn.instance.StartEvent;
import org.finos.fluxnova.bpm.model.bpmn.instance.UserTask;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkflowCompiler} — pure (no Spring context): compile the
 * JSON DSL, then re-read the emitted BPMN and assert its structure. Proves the
 * constrained node graph maps to deployable, schema-valid BPMN (WF-3).
 */
class WorkflowCompilerTest {

    private final WorkflowCompiler compiler = new WorkflowCompiler();
    private final ObjectMapper mapper = new ObjectMapper();

    /** The golden scenario: form submit → email → human review → branch → SF upsert (+ failure email). */
    private static final String GOLDEN_JSON =
            """
            {
              "id": "wf_onboard",
              "version": 3,
              "name": "New client onboarding",
              "trigger": { "capability": "forms", "type": "form.submitted", "config": { "formId": "frm_intake" } },
              "nodes": [
                { "id": "n1", "kind": "action", "capability": "email", "action": "send",
                  "input": { "template": "intake-received" }, "next": "n2" },
                { "id": "n2", "kind": "task", "capability": "forms", "task": "review",
                  "assignee": "queue:ops", "next": "n3" },
                { "id": "n3", "kind": "branch",
                  "conditions": [ { "expr": "amount > 10000", "next": "n4" } ], "else": "end" },
                { "id": "n4", "kind": "action", "capability": "integrations", "provider": "salesforce",
                  "action": "upsertOpportunity", "connection": "conn_sf_primary",
                  "input": { "name": "acme" }, "output": "sfOpportunity",
                  "onError": { "retry": { "maxAttempts": 5, "backoff": "exponential", "initialDelay": "30s" },
                               "fallback": "n5", "deadLetter": true },
                  "next": "end" },
                { "id": "n5", "kind": "action", "capability": "email", "action": "send",
                  "input": { "template": "sf-sync-failed" }, "next": "end" }
              ]
            }
            """;

    private WorkflowDoc parse(String json) throws Exception {
        return mapper.readValue(json, WorkflowDoc.class);
    }

    private BpmnModelInstance reread(CompileResult r) {
        return Bpmn.readModelFromStream(new ByteArrayInputStream(r.bpmnXml().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void compilesGoldenWorkflowToValidBpmn() throws Exception {
        CompileResult result = compiler.compile(parse(GOLDEN_JSON));

        assertThat(result.processId()).isEqualTo("wf_onboard_v3");
        assertThat(result.warnings()).isEmpty();

        BpmnModelInstance m = reread(result); // re-reading validates the XML round-trips

        assertThat((Object) m.getModelElementById("start")).isInstanceOf(StartEvent.class);
        assertThat((Object) m.getModelElementById("end")).isInstanceOf(EndEvent.class);
        assertThat((Object) m.getModelElementById("n1")).isInstanceOf(ServiceTask.class);
        assertThat((Object) m.getModelElementById("n2")).isInstanceOf(UserTask.class);
        assertThat((Object) m.getModelElementById("n3")).isInstanceOf(ExclusiveGateway.class);
        assertThat((Object) m.getModelElementById("n4")).isInstanceOf(ServiceTask.class);
        assertThat((Object) m.getModelElementById("n5")).isInstanceOf(ServiceTask.class);

        // three action nodes → three service tasks
        assertThat(m.getModelElementsByType(ServiceTask.class)).hasSize(3);
    }

    @Test
    void bindsActionTasksToTheConnectorExecutor() throws Exception {
        BpmnModelInstance m = reread(compiler.compile(parse(GOLDEN_JSON)));
        ServiceTask n4 = m.getModelElementById("n4");
        assertThat(n4.getFluxnovaDelegateExpression()).isEqualTo("${connectorExecutor}");
        assertThat(n4.isFluxnovaAsyncBefore()).isTrue();
    }

    @Test
    void wiresErrorBoundaryFromActionToFallback() throws Exception {
        BpmnModelInstance m = reread(compiler.compile(parse(GOLDEN_JSON)));

        BoundaryEvent boundary = m.getModelElementById("err__n4");
        assertThat(boundary).isNotNull();
        assertThat(boundary.getAttachedTo().getId()).isEqualTo("n4");
        assertThat(boundary.getEventDefinitions()).hasSize(1);
        // the boundary routes onward to the fallback node n5
        assertThat(boundary.getOutgoing()).extracting(sf -> sf.getTarget().getId()).containsExactly("n5");
    }

    @Test
    void branchEmitsConditionalAndDefaultFlows() throws Exception {
        BpmnModelInstance m = reread(compiler.compile(parse(GOLDEN_JSON)));

        ExclusiveGateway gw = m.getModelElementById("n3");
        assertThat(gw.getOutgoing()).hasSize(2);
        long conditional = gw.getOutgoing().stream().filter(sf -> sf.getConditionExpression() != null).count();
        assertThat(conditional).isEqualTo(1);
        assertThat(gw.getDefault()).isNotNull();
        // the conditional arm points at n4
        SequenceFlow cond = gw.getOutgoing().stream()
                .filter(sf -> sf.getConditionExpression() != null).findFirst().orElseThrow();
        assertThat(cond.getTarget().getId()).isEqualTo("n4");
    }

    @Test
    void compilesTimerWait() throws Exception {
        String json =
                """
                {
                  "id": "wf_wait", "version": 1,
                  "trigger": { "capability": "forms", "type": "form.submitted" },
                  "nodes": [
                    { "id": "w1", "kind": "wait", "mode": "timer", "duration": "PT2H", "next": "end" }
                  ]
                }
                """;
        BpmnModelInstance m = reread(compiler.compile(parse(json)));
        assertThat((Object) m.getModelElementById("w1")).isInstanceOf(IntermediateCatchEvent.class);
        IntermediateCatchEvent ice = m.getModelElementById("w1");
        assertThat(ice.getEventDefinitions()).hasSize(1);
    }

    @Test
    void rejectsDanglingTarget() throws Exception {
        String json =
                """
                {
                  "id": "wf_bad", "version": 1,
                  "trigger": { "capability": "forms", "type": "form.submitted" },
                  "nodes": [
                    { "id": "n1", "kind": "action", "capability": "email", "action": "send", "next": "ghost" }
                  ]
                }
                """;
        WorkflowDoc doc = parse(json);
        CompileException ex = assertThrows(CompileException.class, () -> compiler.compile(doc));
        assertThat(ex.getMessage()).contains("ghost");
    }

    @Test
    void rejectsEmptyAndTriggerlessWorkflows() throws Exception {
        WorkflowDoc noNodes = parse(
                "{ \"id\": \"w\", \"version\": 1, \"trigger\": { \"capability\": \"forms\", \"type\": \"x\" }, \"nodes\": [] }");
        assertThrows(CompileException.class, () -> compiler.compile(noNodes));

        WorkflowDoc noTrigger = parse(
                "{ \"id\": \"w\", \"version\": 1, \"nodes\": [ { \"id\": \"n1\", \"kind\": \"action\", \"capability\": \"email\", \"action\": \"send\", \"next\": \"end\" } ] }");
        assertThrows(CompileException.class, () -> compiler.compile(noTrigger));
    }
}
