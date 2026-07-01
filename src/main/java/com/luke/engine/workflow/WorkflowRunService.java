package com.luke.engine.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.runtime.Incident;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.IdentityLink;
import org.cibseven.bpm.engine.task.Task;
import org.springframework.stereotype.Service;

/**
 * The WORKFLOW runtime — starting a test instance of a published workflow and
 * inspecting its runs (Pillar 4). Design-time lives in {@link WorkflowDefinitionService};
 * this is the execution/inspection side, reading Camunda/CIBSeven runtime + history.
 *
 * <p>Tenant-scoped throughout: starts are pinned to the caller's tenant deployment, and
 * every read verifies the instance's tenant before returning anything (no cross-tenant leak).
 * Node ids equal BPMN element ids by construction (see {@link WorkflowCompiler}), so the
 * {@code currentActivityIds} / incident {@code activityId} map straight back to authoring nodes
 * for the builder's live highlighting.
 */
@Service
public class WorkflowRunService {

    private final WorkflowDefinitionService definitions;
    private final WorkflowVersionRepository versions;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final TaskService taskService;

    public WorkflowRunService(WorkflowDefinitionService definitions, WorkflowVersionRepository versions,
            RuntimeService runtimeService, HistoryService historyService, TaskService taskService) {
        this.definitions = definitions;
        this.versions = versions;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.taskService = taskService;
    }

    /** Start a test instance of the definition's published version, tenant-scoped. */
    public String startTestRun(String tenantId, String definitionId, Map<String, Object> variables, String user) {
        WorkflowDefinition def = definitions.get(tenantId, definitionId); // 404s if not owned
        Integer published = def.getPublishedVersion();
        if (published == null) {
            throw new WorkflowLifecycleException("Publish the workflow before starting a test run.");
        }
        WorkflowVersion v = versions.findByDefinitionIdAndVersion(definitionId, published)
                .orElseThrow(() -> new WorkflowLifecycleException("Published version " + published + " not found."));
        String processId = v.getProcessId();
        if (processId == null || processId.isBlank()) {
            throw new WorkflowLifecycleException("The published version has no deployed process — re-publish it.");
        }
        ProcessInstance pi = runtimeService.createProcessInstanceByKey(processId)
                .processDefinitionTenantId(tenantId)
                .businessKey("wf-test-" + UUID.randomUUID().toString().substring(0, 8))
                .setVariables(variables != null ? variables : Map.of())
                .execute();
        return pi.getId();
    }

    /** Recent runs (running + finished) for the definition, across all its version process keys. */
    public List<RunSummary> listRuns(String tenantId, String definitionId, int limit) {
        definitions.get(tenantId, definitionId); // ownership check
        List<String> keys = versions.findByDefinitionIdOrderByVersionAsc(definitionId).stream()
                .map(WorkflowVersion::getProcessId).filter(Objects::nonNull).distinct().toList();
        if (keys.isEmpty()) return List.of();
        List<HistoricProcessInstance> hpis = historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKeyIn(keys.toArray(new String[0]))
                .tenantIdIn(tenantId)
                .orderByProcessInstanceStartTime().desc()
                .listPage(0, Math.max(1, Math.min(limit, 100)));
        List<RunSummary> out = new ArrayList<>();
        for (HistoricProcessInstance h : hpis) {
            out.add(new RunSummary(h.getId(), h.getBusinessKey(), h.getState(),
                    h.getEndTime() != null,
                    h.getStartTime() != null ? h.getStartTime().getTime() : null,
                    h.getEndTime() != null ? h.getEndTime().getTime() : null));
        }
        return out;
    }

    /** Full status of one run: state, current activities, active tasks, incidents. */
    public RunDetail runDetail(String tenantId, String instanceId) {
        HistoricProcessInstance h = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult();
        if (h == null || (tenantId != null && h.getTenantId() != null && !tenantId.equals(h.getTenantId()))) {
            return new RunDetail(false, instanceId, null, null, false, null, null, List.of(), List.of(), List.of());
        }
        boolean active = h.getEndTime() == null;

        List<String> activities = active ? new ArrayList<>(runtimeService.getActiveActivityIds(instanceId)) : List.of();

        List<TaskInfo> tasks = new ArrayList<>();
        for (Task t : taskService.createTaskQuery().processInstanceId(instanceId).active().list()) {
            List<String> groups = new ArrayList<>();
            for (IdentityLink il : taskService.getIdentityLinksForTask(t.getId())) {
                if ("candidate".equals(il.getType()) && il.getGroupId() != null) groups.add(il.getGroupId());
            }
            tasks.add(new TaskInfo(t.getId(), t.getName(), t.getTaskDefinitionKey(), t.getAssignee(),
                    t.getCreateTime() != null ? t.getCreateTime().getTime() : null, groups));
        }

        List<IncidentInfo> incidents = new ArrayList<>();
        if (active) {
            for (Incident inc : runtimeService.createIncidentQuery().processInstanceId(instanceId).list()) {
                incidents.add(new IncidentInfo(inc.getId(), inc.getIncidentType(), inc.getIncidentMessage(),
                        inc.getActivityId(),
                        inc.getIncidentTimestamp() != null ? inc.getIncidentTimestamp().getTime() : null));
            }
        }

        return new RunDetail(true, instanceId, h.getProcessDefinitionKey(), h.getState(), !active,
                h.getStartTime() != null ? h.getStartTime().getTime() : null,
                h.getEndTime() != null ? h.getEndTime().getTime() : null,
                activities, tasks, incidents);
    }

    // ── DTOs (data-only; serialized to the builder's Runs panel) ──────────────────
    public record RunSummary(String id, String businessKey, String state, boolean ended, Long startTime, Long endTime) {}

    public record TaskInfo(String id, String name, String activityId, String assignee, Long created, List<String> candidateGroups) {}

    public record IncidentInfo(String id, String type, String message, String activityId, Long timestamp) {}

    public record RunDetail(
            boolean found, String instanceId, String processDefinitionKey, String state, boolean ended,
            Long startTime, Long endTime, List<String> currentActivityIds, List<TaskInfo> activeTasks,
            List<IncidentInfo> incidents) {}
}
