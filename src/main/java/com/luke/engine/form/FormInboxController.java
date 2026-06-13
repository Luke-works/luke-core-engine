package com.luke.engine.form;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Form Inbox: the open user tasks for the caller's tenant (e.g. the
 * "Review Submission" tasks created by the form-intake process). Each task links
 * back to its submission via the business key (= the FormInstance id), so the UI
 * can render the answers and let a reviewer complete the task. Tenant-scoped via
 * {@code X-Tenant-Id}.
 */
@RestController
@RequestMapping("/api/form-inbox")
public class FormInboxController {

    private final TaskService taskService;
    private final RuntimeService runtimeService;

    public FormInboxController(TaskService taskService, RuntimeService runtimeService) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader("X-Tenant-Id") String tenantId) {
        // pid → businessKey for the tenant (one query, avoids per-task lookups).
        Map<String, String> businessKeys = new HashMap<>();
        for (ProcessInstance pi : runtimeService.createProcessInstanceQuery().tenantIdIn(tenantId).list()) {
            if (pi.getBusinessKey() != null) businessKeys.put(pi.getId(), pi.getBusinessKey());
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Task t : taskService.createTaskQuery()
                .tenantIdIn(tenantId)
                .active()
                .orderByTaskCreateTime().desc()
                .list()) {
            Map<String, Object> m = new HashMap<>();
            m.put("taskId", t.getId());
            m.put("name", t.getName());
            m.put("created", t.getCreateTime() != null ? t.getCreateTime().getTime() : null);
            m.put("assignee", t.getAssignee());
            m.put("processInstanceId", t.getProcessInstanceId());
            m.put("processDefinitionKey", stripVersion(t.getProcessDefinitionId()));
            // The submission this task is about (FormInstance id).
            m.put("instanceId", businessKeys.get(t.getProcessInstanceId()));
            out.add(m);
        }
        return out;
    }

    /** Complete a task (optionally claiming it as the actor). */
    @PostMapping("/{taskId}/complete")
    public Map<String, Object> complete(@RequestHeader("X-Tenant-Id") String tenantId,
                                        @RequestHeader(value = "X-User-Id", required = false) String userId,
                                        @PathVariable String taskId) {
        Task t = taskService.createTaskQuery().taskId(taskId).tenantIdIn(tenantId).singleResult();
        if (t == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        }
        if (userId != null && t.getAssignee() == null) {
            taskService.setAssignee(taskId, userId); // record who actioned it
        }
        taskService.complete(taskId);
        return Map.of("ok", true, "taskId", taskId);
    }

    private static String stripVersion(String processDefinitionId) {
        if (processDefinitionId == null) return null;
        int colon = processDefinitionId.indexOf(':');
        return colon > 0 ? processDefinitionId.substring(0, colon) : processDefinitionId;
    }
}
