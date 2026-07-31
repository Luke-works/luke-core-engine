package com.luke.engine.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.TaskService;
import org.finos.fluxnova.bpm.engine.runtime.ProcessInstance;
import org.finos.fluxnova.bpm.engine.runtime.VariableInstance;
import org.finos.fluxnova.bpm.engine.task.Task;
import org.finos.fluxnova.bpm.engine.task.TaskQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant's work inbox: every open user task, whatever created it.
 *
 * <p>Despite the {@code /api/form-inbox} path — kept because it is registered by name in
 * {@link com.luke.engine.config.ApiAuthFilter} and renaming a security-registered route for
 * cosmetics is not worth the risk — this query has never been form-only. It returns all active
 * user tasks for the tenant, which now includes the "Review inbound email" tasks the EMAIL
 * intake creates.
 *
 * <p>Each row carries a {@code kind} saying what it is about, and the fields needed to open it:
 * {@code form} tasks carry {@code instanceId} (the FormInstance holding the answers) and
 * {@code definitionCode}; {@code email} tasks carry {@code emailMessageId}, {@code emailFrom}
 * and {@code emailBox}. Tenant-scoped via {@code X-Tenant-Id}.
 */
@RestController
@RequestMapping("/api/form-inbox")
public class FormInboxController {

    private static final Logger log = LoggerFactory.getLogger(FormInboxController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskService taskService;
    private final RuntimeService runtimeService;

    public FormInboxController(TaskService taskService, RuntimeService runtimeService) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
    }

    /** Max page size — caps the previously unbounded query so one tenant can't load
     *  its entire open-task set into memory in a single request (#23). */
    private static final int MAX_PAGE = 200;
    private static final int DEFAULT_PAGE = 50;

    /** A bounded page of inbox tasks plus the full server-side total (#26). */
    public record PagedInbox(List<Map<String, Object>> items, long total, int firstResult, int maxResults) {}

    /**
     * List the tenant's open user tasks, paged + sorted + searchable server-side (#26).
     * {@code search} matches the task name or assignee; {@code sort} is one of
     * {@code created|name|assignee} with {@code order} asc|desc (default created desc).
     */
    @GetMapping
    public PagedInbox list(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order,
            @RequestParam(defaultValue = "0") int firstResult,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int maxResults) {

        int offset = Math.max(0, firstResult);
        int limit = Math.min(Math.max(1, maxResults), MAX_PAGE);

        // The inbox IS the open user tasks — paginate it (was an unbounded .list()).
        TaskQuery query = taskService.createTaskQuery().tenantIdIn(tenantId).active();
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim() + "%";
            query = query.or().taskNameLike(like).taskAssigneeLike(like).endOr();
        }
        applyOrder(query, sort, order);

        long total = query.count();
        List<Task> tasks = query.listPage(offset, limit);

        // pid → businessKey ONLY for the tasks on this page (was: every PI in the
        // tenant — also unbounded). Bounded by the page size.
        Set<String> pids = tasks.stream()
                .map(Task::getProcessInstanceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> businessKeys = new HashMap<>();
        // pid → FormInstance id. The Camunda business key is the human-readable SM-... key, NOT the
        // FormInstance id (the submission the UI loads + the case file documents are keyed by), so we
        // read the real id from the formMetaData process variable. Best-effort: falls back to the
        // business key if the variable is missing/unreadable.
        Map<String, String> formInstanceIds = new HashMap<>();
        // pid → form definition code, read from the same formMetaData variable (it carries
        // formCode = FormInstance.definitionCode). Lets the inbox group/filter by form
        // without an extra FormInstance lookup.
        Map<String, String> formCodes = new HashMap<>();
        // pid → inbound-email fields, for the EMAIL kind (see the kind note below).
        Map<String, String> emailMessageIds = new HashMap<>();
        Map<String, String> emailFroms = new HashMap<>();
        Map<String, String> emailBoxes = new HashMap<>();
        if (!pids.isEmpty()) {
            for (ProcessInstance pi : runtimeService.createProcessInstanceQuery()
                    .processInstanceIds(pids).list()) {
                if (pi.getBusinessKey() != null) businessKeys.put(pi.getId(), pi.getBusinessKey());
            }
            try {
                // One query for every variable the page needs, form and email alike — a second
                // pass per kind would scale with the number of kinds, not the page size.
                for (VariableInstance v : runtimeService.createVariableInstanceQuery()
                        .processInstanceIdIn(pids.toArray(new String[0]))
                        .variableNameIn("formMetaData", "emailMessageId", "emailFrom", "emailBox")
                        .list()) {
                    String pid = v.getProcessInstanceId();
                    switch (v.getName()) {
                        case "emailMessageId" -> put(emailMessageIds, pid, v.getValue());
                        case "emailFrom" -> put(emailFroms, pid, v.getValue());
                        case "emailBox" -> put(emailBoxes, pid, v.getValue());
                        case "formMetaData" -> {
                            JsonNode meta = readMeta(v.getValue());
                            if (meta == null) break;
                            if (meta.hasNonNull("instanceId")) {
                                formInstanceIds.put(pid, meta.get("instanceId").asText());
                            }
                            if (meta.hasNonNull("formCode")) {
                                formCodes.put(pid, meta.get("formCode").asText());
                            }
                        }
                        default -> { /* not requested */ }
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Inbox: could not resolve task variables (using business keys): {}", e.toString());
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Task t : tasks) {
            Map<String, Object> m = new HashMap<>();
            m.put("taskId", t.getId());
            m.put("name", t.getName());
            m.put("created", t.getCreateTime() != null ? t.getCreateTime().getTime() : null);
            m.put("assignee", t.getAssignee());
            m.put("processInstanceId", t.getProcessInstanceId());
            m.put("processDefinitionKey", stripVersion(t.getProcessDefinitionId()));
            m.put("priority", t.getPriority());
            String pid = t.getProcessInstanceId();
            m.put("businessKey", businessKeys.get(pid)); // the human-readable SM-... key (display/trace)

            // KIND. This query is, and always was, every open user task for the tenant — not only
            // form tasks. Inbound email creates "Review inbound email" tasks here too, and before
            // this field the UI had no way to tell them apart: it read instanceId (which fell back
            // to the business key), tried to load it as a FormInstance, and the row failed to open.
            // Kind is resolved from the variables a process actually carries, so a future task type
            // is a new case here rather than a change to every consumer.
            String emailMessageId = emailMessageIds.get(pid);
            if (emailMessageId != null) {
                m.put("kind", "email");
                m.put("emailMessageId", emailMessageId);
                m.put("emailFrom", emailFroms.get(pid));
                m.put("emailBox", emailBoxes.get(pid));
                // Deliberately null: there is no FormInstance behind an email task, and the
                // business-key fallback below is exactly what made callers think there was.
                m.put("instanceId", null);
                m.put("definitionCode", null);
            } else {
                m.put("kind", "form");
                // The submission this task is about (FormInstance id), preferring the variable-resolved id.
                m.put("instanceId", formInstanceIds.getOrDefault(pid, businessKeys.get(pid)));
                m.put("definitionCode", formCodes.get(pid)); // the form this task belongs to (inbox grouping)
            }
            out.add(m);
        }
        return new PagedInbox(out, total, offset, limit);
    }

    /** Apply a whitelisted task ordering (default: newest first). */
    private static void applyOrder(TaskQuery query, String sort, String order) {
        TaskQuery ordered = switch (sort == null ? "" : sort) {
            case "name" -> query.orderByTaskName();
            case "assignee" -> query.orderByTaskAssignee();
            default -> query.orderByTaskCreateTime();
        };
        if ("asc".equalsIgnoreCase(order)) {
            ordered.asc();
        } else {
            ordered.desc();
        }
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

    /** Record a string-valued process variable, ignoring nulls and blanks. */
    private static void put(Map<String, String> target, String pid, Object value) {
        if (value == null) return;
        String s = String.valueOf(value).trim();
        if (!s.isEmpty()) target.put(pid, s);
    }

    /** Parse the formMetaData variable (a Spin JSON node or a JSON string — both render JSON via
     *  toString()) into a JsonNode, or null if absent/unreadable. Mirrors FormInstanceWriteBackDelegate. */
    private static JsonNode readMeta(Object formMetaData) {
        if (formMetaData == null) return null;
        try {
            return MAPPER.readTree(formMetaData.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripVersion(String processDefinitionId) {
        if (processDefinitionId == null) return null;
        int colon = processDefinitionId.indexOf(':');
        return colon > 0 ? processDefinitionId.substring(0, colon) : processDefinitionId;
    }
}
