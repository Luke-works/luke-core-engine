package com.luke.engine.document;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.finos.fluxnova.bpm.engine.HistoryService;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.TaskService;
import org.finos.fluxnova.bpm.engine.history.HistoricIdentityLinkLog;
import org.finos.fluxnova.bpm.engine.history.HistoricProcessInstance;
import org.finos.fluxnova.bpm.engine.history.HistoricTaskInstance;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.finos.fluxnova.bpm.engine.task.IdentityLink;
import org.finos.fluxnova.bpm.engine.task.IdentityLinkType;
import org.finos.fluxnova.bpm.engine.task.Task;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The real DOC-4 context resolver: decides whether a user may see a document given its Camunda
 * task/process, using CANDIDATE GROUPS (resolved live, incl. historic identity links for completed
 * tasks). Mirrors how {@code FormInboxController}/{@code RestApiAuthFilter} read groups + identity links.
 *
 * <ul>
 *   <li><b>task attachment</b> ({@code taskId} set) → allow if the user is the task's assignee, a
 *       candidate user, or a member of one of its candidate groups.</li>
 *   <li><b>case attachment</b> ({@code taskId} null) → allow if the user is a process participant: the
 *       starter, or a member of any candidate group used on the process; before the process exists
 *       (start-with-attachments), the uploader (createdBy) can see their own.</li>
 * </ul>
 *
 * The Camunda lookups are isolated in {@code protected} "fact" methods so the decision logic can be
 * unit-tested without a running engine.
 */
@Component
public class CamundaTaskAccessResolver implements TaskAccessResolver {

    private final TaskService taskService;
    private final IdentityService identityService;
    private final HistoryService historyService;

    public CamundaTaskAccessResolver(TaskService taskService, IdentityService identityService,
                                     HistoryService historyService) {
        this.taskService = taskService;
        this.identityService = identityService;
        this.historyService = historyService;
    }

    @Override
    public boolean canAccess(String tenantId, String userId, Document doc) {
        if (!StringUtils.hasText(userId)) return false;
        if (StringUtils.hasText(doc.getTaskId())) {
            return hasTaskAccess(userId, doc.getTaskId());
        }
        // case-level (taskId null)
        if (!StringUtils.hasText(doc.getProcessInstanceId())) {
            return userId.equals(doc.getCreatedBy());          // not started yet → only the uploader
        }
        return userId.equals(doc.getCreatedBy())
                || isProcessParticipant(userId, doc.getProcessInstanceId());
    }

    @Override
    public boolean canUpload(String tenantId, String userId, String processRef,
                             String processInstanceId, String taskId) {
        if (!StringUtils.hasText(userId)) return false;
        if (StringUtils.hasText(taskId)) {
            return hasTaskAccess(userId, taskId);              // attach to a task you can act on
        }
        return true;                                            // case-level upload: capability already gates
    }

    // ── decisions ──────────────────────────────────────────────────────────────
    boolean hasTaskAccess(String userId, String taskId) {
        TaskIdentity ti = taskIdentity(taskId);
        if (userId.equals(ti.assignee())) return true;
        if (ti.candidateUsers().contains(userId)) return true;
        return !Collections.disjoint(ti.candidateGroups(), userGroups(userId));
    }

    boolean isProcessParticipant(String userId, String processInstanceId) {
        if (userId.equals(processStarter(processInstanceId))) return true;
        return !Collections.disjoint(processCandidateGroups(processInstanceId), userGroups(userId));
    }

    // ── Camunda "facts" (protected → overridable in unit tests) ──────────────────
    /** Group ids the user belongs to. */
    protected Set<String> userGroups(String userId) {
        return identityService.createGroupQuery().groupMember(userId).list()
                .stream().map(Group::getId).collect(Collectors.toSet());
    }

    /** Assignee + candidate users/groups for a task — runtime identity links, else historic. */
    protected TaskIdentity taskIdentity(String taskId) {
        Set<String> users = new HashSet<>();
        Set<String> groups = new HashSet<>();
        String assignee = null;
        Task task = safe(() -> taskService.createTaskQuery().taskId(taskId).singleResult());
        if (task != null) {
            assignee = task.getAssignee();
            List<IdentityLink> links = safeList(() -> taskService.getIdentityLinksForTask(taskId));
            for (IdentityLink il : links) {
                if (IdentityLinkType.CANDIDATE.equals(il.getType())) {
                    if (il.getGroupId() != null) groups.add(il.getGroupId());
                    if (il.getUserId() != null) users.add(il.getUserId());
                } else if (IdentityLinkType.ASSIGNEE.equals(il.getType()) && il.getUserId() != null) {
                    assignee = il.getUserId();
                }
            }
            return new TaskIdentity(assignee, users, groups);
        }
        // completed task → historic identity link log (net of add/delete)
        for (HistoricIdentityLinkLog il : safeList(() ->
                historyService.createHistoricIdentityLinkLogQuery().taskId(taskId).list())) {
            boolean add = "add".equals(il.getOperationType());
            if (IdentityLinkType.CANDIDATE.equals(il.getType())) {
                if (il.getGroupId() != null) toggle(groups, il.getGroupId(), add);
                if (il.getUserId() != null) toggle(users, il.getUserId(), add);
            } else if (IdentityLinkType.ASSIGNEE.equals(il.getType()) && add) {
                assignee = il.getUserId();
            }
        }
        HistoricTaskInstance ht = safe(() ->
                historyService.createHistoricTaskInstanceQuery().taskId(taskId).singleResult());
        if (ht != null && ht.getAssignee() != null) assignee = ht.getAssignee();
        return new TaskIdentity(assignee, users, groups);
    }

    /** The user who started the process instance. */
    protected String processStarter(String processInstanceId) {
        HistoricProcessInstance pi = safe(() -> historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult());
        return pi != null ? pi.getStartUserId() : null;
    }

    /** Candidate groups used on ANY task of the process (runtime active + historic). */
    protected Set<String> processCandidateGroups(String processInstanceId) {
        Set<String> groups = new HashSet<>();
        for (Task t : safeList(() -> taskService.createTaskQuery()
                .processInstanceId(processInstanceId).list())) {
            for (IdentityLink il : safeList(() -> taskService.getIdentityLinksForTask(t.getId()))) {
                if (IdentityLinkType.CANDIDATE.equals(il.getType()) && il.getGroupId() != null) {
                    groups.add(il.getGroupId());
                }
            }
        }
        for (HistoricTaskInstance ht : safeList(() -> historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).list())) {
            for (HistoricIdentityLinkLog il : safeList(() -> historyService
                    .createHistoricIdentityLinkLogQuery().taskId(ht.getId()).list())) {
                if (IdentityLinkType.CANDIDATE.equals(il.getType()) && il.getGroupId() != null
                        && "add".equals(il.getOperationType())) {
                    groups.add(il.getGroupId());
                }
            }
        }
        return groups;
    }

    /** Assignee + candidate users/groups of a task. */
    protected record TaskIdentity(String assignee, Set<String> candidateUsers, Set<String> candidateGroups) {}

    private static void toggle(Set<String> set, String value, boolean add) {
        if (add) set.add(value); else set.remove(value);
    }

    /** Camunda queries can throw if an id is unknown/stale — treat any failure as "no fact". */
    private static <T> T safe(java.util.function.Supplier<T> q) {
        try { return q.get(); } catch (RuntimeException e) { return null; }
    }

    private static <T> List<T> safeList(java.util.function.Supplier<List<T>> q) {
        try { List<T> r = q.get(); return r != null ? r : List.of(); } catch (RuntimeException e) { return List.of(); }
    }
}
