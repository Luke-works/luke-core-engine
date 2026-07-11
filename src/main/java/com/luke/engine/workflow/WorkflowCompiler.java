package com.luke.engine.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.model.bpmn.Bpmn;
import org.finos.fluxnova.bpm.model.bpmn.BpmnModelInstance;
import org.finos.fluxnova.bpm.model.bpmn.instance.Activity;
import org.finos.fluxnova.bpm.model.bpmn.instance.BoundaryEvent;
import org.finos.fluxnova.bpm.model.bpmn.instance.BpmnModelElementInstance;
import org.finos.fluxnova.bpm.model.bpmn.instance.ConditionExpression;
import org.finos.fluxnova.bpm.model.bpmn.instance.Definitions;
import org.finos.fluxnova.bpm.model.bpmn.instance.EndEvent;
import org.finos.fluxnova.bpm.model.bpmn.instance.ErrorEventDefinition;
import org.finos.fluxnova.bpm.model.bpmn.instance.ExclusiveGateway;
import org.finos.fluxnova.bpm.model.bpmn.instance.FlowNode;
import org.finos.fluxnova.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.finos.fluxnova.bpm.model.bpmn.instance.Message;
import org.finos.fluxnova.bpm.model.bpmn.instance.MessageEventDefinition;
import org.finos.fluxnova.bpm.model.bpmn.instance.ParallelGateway;
import org.finos.fluxnova.bpm.model.bpmn.instance.Process;
import org.finos.fluxnova.bpm.model.bpmn.instance.SequenceFlow;
import org.finos.fluxnova.bpm.model.bpmn.instance.ServiceTask;
import org.finos.fluxnova.bpm.model.bpmn.instance.StartEvent;
import org.finos.fluxnova.bpm.model.bpmn.instance.TimeDuration;
import org.finos.fluxnova.bpm.model.bpmn.instance.TimerEventDefinition;
import org.finos.fluxnova.bpm.model.bpmn.instance.UserTask;
import org.springframework.stereotype.Component;

/**
 * Compiles a {@link WorkflowDoc} (the friendly JSON DSL) into schema-valid BPMN
 * 2.0 that Camunda / CIBSeven can deploy and run. This is the server-authoritative
 * translation: the builder validates for UX, but only what this class emits is
 * ever executed.
 *
 * <p><b>Design.</b> The BPMN is built directly against the model API (not the
 * linear fluent builder) so arbitrary node graphs — branches, parallel splits,
 * boundary-error routing — map deterministically. Every workflow node's id becomes
 * the BPMN flow-element id verbatim, so at runtime a Camunda {@code activityId}
 * resolves straight back to the authoring node (and its capability/action from the
 * stored JSON) without embedding capability metadata in the BPMN.
 *
 * <p><b>Node → BPMN mapping.</b>
 * <ul>
 *   <li>{@code trigger} → start event (execution binding added in WF-11)</li>
 *   <li>{@code action}  → service task</li>
 *   <li>{@code task}    → user task</li>
 *   <li>{@code branch}  → exclusive gateway (conditional flows + default/else)</li>
 *   <li>{@code parallel}→ parallel gateway (fork; join is a V1 limitation, see warnings)</li>
 *   <li>{@code wait}    → intermediate catch event (timer or message)</li>
 *   <li>{@code onError.fallback} → error boundary event → fallback node</li>
 * </ul>
 * The reserved target {@code "end"} (and {@code null}/absent) routes to the single
 * end event.
 */
@Component
public class WorkflowCompiler {

    /** Compile {@code doc} to deployable BPMN, or throw {@link CompileException}. */
    public CompileResult compile(WorkflowDoc doc) {
        return new Compilation(doc).run();
    }

    private static boolean isTerminal(String target) {
        return target == null || target.isBlank() || "end".equals(target);
    }

    private static String deriveProcessId(WorkflowDoc doc) {
        String base = (doc.id() == null || doc.id().isBlank()) ? "workflow" : doc.id();
        String safe = base.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        if (!safe.matches("[A-Za-z_].*")) safe = "_" + safe;
        int v = doc.version() == null ? 0 : doc.version();
        return safe + "_v" + v;
    }

    /** One compilation pass — holds all mutable state for a single document. */
    private static final class Compilation {

        private final WorkflowDoc doc;
        private final String processId;
        private final BpmnModelInstance model;
        private final Process process;
        private final Map<String, FlowNode> byId = new HashMap<>();
        private final List<String> warnings = new ArrayList<>();
        private EndEvent end;
        private int seq = 0;

        Compilation(WorkflowDoc doc) {
            this.doc = doc;
            this.processId = deriveProcessId(doc);
            this.model = Bpmn.createEmptyModel();
            Definitions defs = model.newInstance(Definitions.class);
            defs.setTargetNamespace("http://lukeflow.com/bpmn");
            model.setDefinitions(defs);
            this.process = model.newInstance(Process.class, processId);
            process.setExecutable(true);
            defs.addChildElement(process);
        }

        CompileResult run() {
            if (doc.nodes() == null || doc.nodes().isEmpty()) {
                throw new CompileException("Workflow has no nodes");
            }
            if (doc.trigger() == null || doc.trigger().capability() == null || doc.trigger().type() == null) {
                throw new CompileException("Workflow has no valid trigger (capability + type required)");
            }

            StartEvent start = add(StartEvent.class, "start");
            this.end = add(EndEvent.class, "end");

            for (WorkflowNode n : doc.nodes()) createPrimary(n);

            String startId = doc.start() != null ? doc.start() : doc.nodes().get(0).id();
            FlowNode startNode = byId.get(startId);
            if (startNode == null) {
                throw new CompileException("start references unknown node '" + startId + "'");
            }
            connect(start, startNode);

            for (WorkflowNode n : doc.nodes()) wire(n);

            Bpmn.validateModel(model);
            return new CompileResult(processId, Bpmn.convertToString(model), List.copyOf(warnings));
        }

        private void createPrimary(WorkflowNode n) {
            if (n.id() == null || n.id().isBlank()) {
                throw new CompileException("A node is missing its id");
            }
            if (byId.containsKey(n.id())) {
                throw new CompileException("Duplicate node id '" + n.id() + "'");
            }
            String kind = n.kind() == null ? "" : n.kind();
            FlowNode fn = switch (kind) {
                case "action" -> {
                    // Outbound rail: a delegate-backed, async service task. asyncBefore puts it on a
                    // job so the executor's transient failures retry via the job executor (WF-11).
                    ServiceTask st = add(ServiceTask.class, n.id());
                    st.setFluxnovaDelegateExpression("${connectorExecutor}");
                    st.setFluxnovaAsyncBefore(true);
                    yield st;
                }
                case "task" -> add(UserTask.class, n.id());
                case "branch" -> add(ExclusiveGateway.class, n.id());
                case "parallel" -> add(ParallelGateway.class, n.id());
                case "wait" -> add(IntermediateCatchEvent.class, n.id());
                default -> throw new CompileException("Node '" + n.id() + "' has unknown kind '" + kind + "'");
            };
            if (n.name() != null) fn.setName(n.name());
            byId.put(n.id(), fn);
        }

        private void wire(WorkflowNode n) {
            FlowNode self = byId.get(n.id());
            switch (n.kind()) {
                case "action", "task" -> {
                    connect(self, resolve(n.next()));
                    wireBoundary(n, self);
                }
                case "branch" -> wireBranch(n, (ExclusiveGateway) self);
                case "parallel" -> wireParallel(n, self);
                case "wait" -> {
                    wireWait(n, (IntermediateCatchEvent) self);
                    connect(self, resolve(n.next()));
                }
                default -> { /* unreachable — createPrimary already rejected it */ }
            }
        }

        private void wireBoundary(WorkflowNode n, FlowNode self) {
            ErrorPolicy ep = n.onError();
            if (ep == null || isTerminal(ep.fallback())) return;
            BoundaryEvent be = add(BoundaryEvent.class, "err__" + n.id());
            be.setAttachedTo((Activity) self);
            ErrorEventDefinition eed = model.newInstance(ErrorEventDefinition.class);
            be.getEventDefinitions().add(eed);
            connect(be, resolve(ep.fallback()));
        }

        private void wireBranch(WorkflowNode n, ExclusiveGateway gw) {
            List<BranchCondition> conds = n.conditions() == null ? List.of() : n.conditions();
            for (BranchCondition c : conds) {
                if (c == null) continue;
                SequenceFlow sf = connect(gw, resolve(c.next()));
                ConditionExpression ce = model.newInstance(ConditionExpression.class);
                ce.setTextContent("${" + (c.expr() == null ? "false" : c.expr()) + "}");
                sf.setConditionExpression(ce);
            }
            // A default/else flow so the gateway is never stuck (routes to "end" when absent).
            SequenceFlow def = connect(gw, resolve(n.elseTarget()));
            gw.setDefault(def);
        }

        private void wireParallel(WorkflowNode n, FlowNode fork) {
            List<String> branches = n.branches() == null ? List.of() : n.branches();
            for (String b : branches) connect(fork, resolve(b));
            if (!isTerminal(n.join())) {
                warnings.add("Node '" + n.id()
                        + "': parallel join is not compiled in V1; branches continue via their own next.");
            }
        }

        private void wireWait(WorkflowNode n, IntermediateCatchEvent ice) {
            if ("event".equals(n.mode())) {
                MessageEventDefinition med = model.newInstance(MessageEventDefinition.class);
                Message msg = model.newInstance(Message.class, "msg__" + n.id());
                String name = n.event() != null
                        ? n.event().capability() + "." + n.event().type()
                        : "wait__" + n.id();
                msg.setName(name);
                model.getDefinitions().addChildElement(msg);
                med.setMessage(msg);
                ice.getEventDefinitions().add(med);
            } else {
                TimerEventDefinition ted = model.newInstance(TimerEventDefinition.class);
                TimeDuration td = model.newInstance(TimeDuration.class);
                td.setTextContent(n.duration() == null ? "PT0S" : n.duration());
                ted.setTimeDuration(td);
                ice.getEventDefinitions().add(ted);
            }
        }

        private FlowNode resolve(String target) {
            if (isTerminal(target)) return end;
            FlowNode fn = byId.get(target);
            if (fn == null) throw new CompileException("Node references unknown target '" + target + "'");
            return fn;
        }

        private <T extends BpmnModelElementInstance> T add(Class<T> type, String id) {
            T el = model.newInstance(type, id);
            process.addChildElement(el);
            return el;
        }

        private SequenceFlow connect(FlowNode from, FlowNode to) {
            SequenceFlow sf = model.newInstance(SequenceFlow.class, "f__" + (seq++) + "__" + from.getId());
            process.addChildElement(sf);
            sf.setSource(from);
            sf.setTarget(to);
            from.getOutgoing().add(sf);
            to.getIncoming().add(sf);
            return sf;
        }
    }
}
