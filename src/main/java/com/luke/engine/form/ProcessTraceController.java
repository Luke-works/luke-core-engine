package com.luke.engine.form;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.task.IdentityLink;
import org.cibseven.bpm.engine.task.Task;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only end-to-end trace of a form submission's process: did it start, is it
 * running or done, and is it currently sitting in a user task (and which)? Used
 * by the consumer UI's Form Instances utility. Tenant-scoped — the caller's
 * {@code X-Tenant-Id} must own the process instance.
 */
@RestController
@RequestMapping("/api/process-trace")
public class ProcessTraceController {

    private final TaskService taskService;
    private final HistoryService historyService;

    public ProcessTraceController(TaskService taskService, HistoryService historyService) {
        this.taskService = taskService;
        this.historyService = historyService;
    }

    @GetMapping("/{processInstanceId}")
    public Map<String, Object> trace(@RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                     @PathVariable String processInstanceId) {
        Map<String, Object> out = new HashMap<>();

        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();

        // Not found, or belongs to another tenant → report not-found (no leak).
        if (hpi == null || (tenantId != null && hpi.getTenantId() != null && !tenantId.equals(hpi.getTenantId()))) {
            out.put("found", false);
            return out;
        }

        out.put("found", true);
        out.put("processInstanceId", processInstanceId);
        out.put("processDefinitionKey", hpi.getProcessDefinitionKey());
        out.put("state", hpi.getState()); // ACTIVE | COMPLETED | EXTERNALLY_TERMINATED | INTERNALLY_TERMINATED | SUSPENDED
        out.put("ended", hpi.getEndTime() != null);
        out.put("startTime", hpi.getStartTime() != null ? hpi.getStartTime().getTime() : null);
        out.put("endTime", hpi.getEndTime() != null ? hpi.getEndTime().getTime() : null);

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Task t : taskService.createTaskQuery().processInstanceId(processInstanceId).active().list()) {
            Map<String, Object> tm = new HashMap<>();
            tm.put("id", t.getId());
            tm.put("name", t.getName());
            tm.put("assignee", t.getAssignee());
            tm.put("created", t.getCreateTime() != null ? t.getCreateTime().getTime() : null);
            List<String> groups = new ArrayList<>();
            for (IdentityLink il : taskService.getIdentityLinksForTask(t.getId())) {
                if ("candidate".equals(il.getType()) && il.getGroupId() != null) groups.add(il.getGroupId());
            }
            tm.put("candidateGroups", groups);
            tasks.add(tm);
        }
        out.put("activeTasks", tasks);
        out.put("landedInUserTask", !tasks.isEmpty());
        return out;
    }
}
