package com.luke.engine.capability.access;

import com.luke.engine.capability.capability.Capability;
import com.luke.engine.capability.capability.CapabilityRepository;
import com.luke.engine.tenant.CapabilityOwnership;
import com.luke.engine.tenant.UserDirectory;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.TaskService;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.finos.fluxnova.bpm.engine.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Access-request / approval workflow, orchestrated by Camunda.
 *
 * <p>Raising a request writes the request row AND an outbox row in one transaction;
 * {@link AccessRequestOutboxConsumer} then starts {@code AccessRequestApprovalProcess}, which
 * routes an approval task to the capability's RESOURCE OWNER GROUP (see
 * {@link CapabilityOwnership}, falling back to the tenant owners). This controller is the HTTP
 * face of that process: approve/deny complete the owner's task, resubmit/withdraw complete the
 * requester's task after a rejection. The process — not this class — grants access, via
 * {@link AccessProvisioningDelegate}.
 *
 * <p><b>Rejection is not the end.</b> Denying returns the request to the requester
 * ({@link AccessRequest#RETURNED}) with the reason, and they revise and resubmit — back to the
 * same owners — or withdraw. {@link AccessRequest#DENIED} now only arises on the legacy
 * non-orchestrated path below.
 *
 * <p><b>Legacy/degraded path.</b> Requests raised before this workflow existed have no process
 * instance, and a request whose outbox row has not drained yet has no task. Rather than fail,
 * approve/deny fall back to deciding the request directly, exactly as before — so an engine
 * problem downgrades the feature instead of blocking access administration entirely.
 *
 * <p>Member routes act as the gateway-asserted caller (X-User-Id) within the active tenant
 * (X-Tenant-Id). Org routes require a resource owner of the capability, a tenant owner, or a
 * platform operator.
 */
@RestController
@RequestMapping("/api")
public class AccessRequestController {

    private static final Logger log = LoggerFactory.getLogger(AccessRequestController.class);

    private static final String CAMUNDA_ADMIN_GROUP = "camunda-admin";

    /** Task definition keys in AccessRequestApprovalProcess.bpmn. */
    static final String TASK_APPROVE = "Activity_approve";
    static final String TASK_REWORK = "Activity_rework";

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    private final AccessRequestRepository requests;
    private final AccessRequestOutboxRepository outbox;
    private final CapabilityGrantRepository grants;
    private final CapabilityGrantController grantController;
    private final CapabilityAccessService access;
    private final CapabilityRepository capabilities;
    private final UserDirectory userDirectory;
    private final IdentityService identityService;
    private final TaskService taskService;
    private final RuntimeService runtimeService;

    public AccessRequestController(AccessRequestRepository requests,
                                   AccessRequestOutboxRepository outbox,
                                   CapabilityGrantRepository grants,
                                   CapabilityGrantController grantController,
                                   CapabilityAccessService access,
                                   CapabilityRepository capabilities,
                                   UserDirectory userDirectory,
                                   IdentityService identityService,
                                   TaskService taskService,
                                   RuntimeService runtimeService) {
        this.requests = requests;
        this.outbox = outbox;
        this.grants = grants;
        this.grantController = grantController;
        this.access = access;
        this.capabilities = capabilities;
        this.userDirectory = userDirectory;
        this.identityService = identityService;
        this.taskService = taskService;
        this.runtimeService = runtimeService;
    }

    /* ── request / response bodies ──────────────────────────── */
    public record CreateRequest(String capabilityCode, String level, String note) {}
    public record ApproveBody(String level, String note) {}
    public record DenyBody(String note) {}
    public record ResubmitBody(String level, String note) {}

    /* ── member endpoints (act as X-User-Id) ────────────────── */

    /**
     * Raise a request, and queue the approval process for it in the SAME transaction — so a
     * request is never accepted without something durable that will route it to an approver.
     */
    @PostMapping("/access-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AccessRequest create(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader("X-User-Id") String userId,
                                @RequestBody CreateRequest body) {
        requireTenant(tenantId);
        requireUser(userId);
        String code = body.capabilityCode();
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "capabilityCode is required");
        }
        if (!CapabilityLevel.isValid(body.level())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "level must be 'read', 'contributor' or 'read-write'");
        }
        // Capability must exist AND the tenant must be ACTIVE-subscribed — otherwise
        // it's not something this org can grant at all.
        if (capabilities.findByCode(code).isEmpty() || !access.tenantHasCapability(tenantId, code)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    code + " is not available for your org");
        }
        // Already at or above the requested level → nothing to request. Compared by RANK: the
        // old action-class comparison rejected every upgrade through `contributor` (a `read`
        // holder "already had" contributor; a contributor "already had" read-write).
        String current = grants.findByTenantIdAndUserIdAndCapabilityCode(tenantId, userId, code)
                .map(CapabilityGrant::getLevel).orElse(null);
        if (CapabilityLevel.atLeast(current, body.level())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "you already have this access");
        }
        // One open request per (tenant, user, capability) — including one sitting with the
        // requester after a return, which they should revise rather than duplicate.
        if (requests.existsByTenantIdAndUserIdAndCapabilityCodeAndStatus(
                tenantId, userId, code, AccessRequest.PENDING)
                || requests.existsByTenantIdAndUserIdAndCapabilityCodeAndStatus(
                tenantId, userId, code, AccessRequest.RETURNED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request already open");
        }
        AccessRequest req = new AccessRequest(tenantId, userId, code, body.level());
        req.setNote(trimToNull(body.note()));
        AccessRequest saved = requests.save(req);
        outbox.save(new AccessRequestOutbox(tenantId, saved.getId(),
                AccessApprovalProcessService.businessKey(saved.getId())));
        return withNames(saved);
    }

    /** The caller's own requests, any status, newest first. */
    @GetMapping("/my-access-requests")
    public List<AccessRequest> myRequests(@RequestHeader("X-Tenant-Id") String tenantId,
                                          @RequestHeader("X-User-Id") String userId) {
        requireTenant(tenantId);
        requireUser(userId);
        return withNames(requests.findByTenantIdAndUserIdOrderByRequestedAtDesc(tenantId, userId));
    }

    /** Caller cancels their OWN pending request, terminating the approval process with it. */
    @PostMapping("/access-requests/{id}/cancel")
    public AccessRequest cancel(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader("X-User-Id") String userId,
                                @PathVariable String id) {
        requireTenant(tenantId);
        requireUser(userId);
        AccessRequest req = ownRequest(tenantId, userId, id);
        if (!AccessRequest.PENDING.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a pending request can be cancelled");
        }
        deleteProcess(req, "cancelled by requester");
        req.setStatus(AccessRequest.CANCELLED);
        return withNames(requests.save(req));
    }

    /**
     * Requester revises a RETURNED request and sends it back to the owners. Completing the rework
     * task loops the process to the approval task; the level/justification revision rides along.
     */
    @PostMapping("/access-requests/{id}/resubmit")
    public AccessRequest resubmit(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @RequestHeader("X-User-Id") String userId,
                                  @PathVariable String id,
                                  @RequestBody(required = false) ResubmitBody body) {
        requireTenant(tenantId);
        requireUser(userId);
        AccessRequest req = ownRequest(tenantId, userId, id);
        if (!AccessRequest.RETURNED.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a request that was returned to you can be resubmitted");
        }
        Task task = findTask(req, tenantId, TASK_REWORK);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This request is no longer waiting on you");
        }
        String level = (body != null && body.level() != null && !body.level().isBlank())
                ? body.level() : req.getRequestedLevel();
        if (!CapabilityLevel.isValid(level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "level must be 'read', 'contributor' or 'read-write'");
        }
        String note = trimToNull(body != null ? body.note() : null);
        if (note != null) {
            req.setNote(note);
            requests.save(req);
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put(AccessProcessVariables.RESUBMIT, true);
        vars.put(AccessProcessVariables.REQUESTED_LEVEL, level);
        taskService.setAssignee(task.getId(), userId);
        taskService.complete(task.getId(), vars);
        return withNames(reload(req));
    }

    /** Requester gives up on a RETURNED request; the process closes it out. */
    @PostMapping("/access-requests/{id}/withdraw")
    public AccessRequest withdraw(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @RequestHeader("X-User-Id") String userId,
                                  @PathVariable String id) {
        requireTenant(tenantId);
        requireUser(userId);
        AccessRequest req = ownRequest(tenantId, userId, id);
        if (!AccessRequest.RETURNED.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a request that was returned to you can be withdrawn");
        }
        Task task = findTask(req, tenantId, TASK_REWORK);
        if (task == null) {
            // No live task (legacy row / engine unavailable): close it out directly.
            req.setStatus(AccessRequest.CANCELLED);
            req.setDecidedAt(LocalDateTime.now());
            return withNames(requests.save(req));
        }
        taskService.setAssignee(task.getId(), userId);
        taskService.complete(task.getId(), Map.of(AccessProcessVariables.RESUBMIT, false));
        return withNames(reload(req));
    }

    /* ── org endpoints (resource owner / tenant owner / operator) ── */

    /** The tenant's requests in {@code status} (default PENDING), newest first. */
    @GetMapping("/org/access-requests")
    public List<AccessRequest> orgRequests(@RequestHeader("X-Tenant-Id") String tenantId,
                                           @RequestHeader("X-User-Id") String userId,
                                           @RequestParam(defaultValue = "PENDING") String status) {
        requireTenant(tenantId);
        requireUser(userId);
        // Tenant owners and operators see the whole queue.
        if (isTenantAdmin(userId, tenantId)) {
            return withNames(requests.findByTenantIdAndStatusOrderByRequestedAtDesc(tenantId, status));
        }
        // A resource owner sees only the capabilities they own — and is authorized on OWNING
        // something, not on the queue happening to be non-empty, so a quiet day reads as an
        // empty list rather than a 403 (and a non-owner still can't probe the queue).
        if (!CapabilityOwnership.ownsAny(identityService, userId, tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Requires org owner, or resource owner of a capability");
        }
        List<AccessRequest> mine = requests.findByTenantIdAndStatusOrderByRequestedAtDesc(tenantId, status)
                .stream()
                .filter(r -> CapabilityOwnership.isOwner(identityService, userId, tenantId, r.getCapabilityCode()))
                .toList();
        return withNames(mine);
    }

    /**
     * Approve: complete the owner's task, letting the process provision the grant at the
     * requested level unless {@code level} overrides it.
     */
    @PostMapping("/org/access-requests/{id}/approve")
    public AccessRequest approve(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestHeader("X-User-Id") String userId,
                                 @PathVariable String id,
                                 @RequestBody(required = false) ApproveBody body) {
        requireTenant(tenantId);
        requireUser(userId);
        AccessRequest req = load(tenantId, id);
        requireApprover(userId, tenantId, req.getCapabilityCode());
        if (!AccessRequest.PENDING.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a pending request can be approved");
        }
        String level = (body != null && body.level() != null && !body.level().isBlank())
                ? body.level() : req.getRequestedLevel();
        if (!CapabilityLevel.isValid(level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "level must be 'read', 'contributor' or 'read-write'");
        }
        String note = trimToNull(body != null ? body.note() : null);

        Task task = findTask(req, tenantId, TASK_APPROVE);
        if (task != null) {
            Map<String, Object> vars = new HashMap<>();
            vars.put(AccessProcessVariables.APPROVED, true);
            vars.put(AccessProcessVariables.GRANT_LEVEL, level);
            vars.put(AccessProcessVariables.DECIDED_BY, userId);
            vars.put(AccessProcessVariables.DECISION_NOTE, note);
            taskService.setAssignee(task.getId(), userId);
            taskService.complete(task.getId(), vars);
            return withNames(reload(req));
        }
        return withNames(approveDirectly(req, level, note, userId));
    }

    /**
     * Reject: complete the owner's task with a reason, which RETURNS the request to the requester
     * to revise or withdraw. (Kept at /deny so existing clients keep working.)
     */
    @PostMapping("/org/access-requests/{id}/deny")
    public AccessRequest deny(@RequestHeader("X-Tenant-Id") String tenantId,
                              @RequestHeader("X-User-Id") String userId,
                              @PathVariable String id,
                              @RequestBody(required = false) DenyBody body) {
        requireTenant(tenantId);
        requireUser(userId);
        AccessRequest req = load(tenantId, id);
        requireApprover(userId, tenantId, req.getCapabilityCode());
        if (!AccessRequest.PENDING.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a pending request can be denied");
        }
        String note = trimToNull(body != null ? body.note() : null);

        Task task = findTask(req, tenantId, TASK_APPROVE);
        if (task != null) {
            Map<String, Object> vars = new HashMap<>();
            vars.put(AccessProcessVariables.APPROVED, false);
            vars.put(AccessProcessVariables.DECIDED_BY, userId);
            vars.put(AccessProcessVariables.DECISION_NOTE, note);
            taskService.setAssignee(task.getId(), userId);
            taskService.complete(task.getId(), vars);
            return withNames(reload(req));
        }
        // No process: terminal DENIED, the pre-workflow behaviour.
        req.setStatus(AccessRequest.DENIED);
        req.setDecisionNote(note);
        req.setDecidedBy(userId);
        req.setDecidedAt(LocalDateTime.now());
        return withNames(requests.save(req));
    }

    /* ── process helpers ────────────────────────────────────── */

    /** The active task of {@code key} for this request's process, or null (legacy/not started/gone). */
    private Task findTask(AccessRequest req, String tenantId, String key) {
        if (req.getProcessInstanceId() == null) return null;
        try {
            return taskService.createTaskQuery()
                    .processInstanceId(req.getProcessInstanceId())
                    .taskDefinitionKey(key)
                    .tenantIdIn(tenantId)
                    .active()
                    .singleResult();
        } catch (RuntimeException e) {
            log.warn("Task lookup failed for request {} ({}): {}", req.getId(), key, e.getMessage());
            return null;
        }
    }

    /** Grant + record the decision without a process (legacy rows, or the engine not yet started). */
    private AccessRequest approveDirectly(AccessRequest req, String level, String note, String userId) {
        log.info("Approving request {} without a process instance (legacy/degraded path)", req.getId());
        grantController.setGrant(req.getTenantId(), req.getUserId(), req.getCapabilityCode(), userId,
                new CapabilityGrantController.GrantBody(level));
        req.setStatus(AccessRequest.APPROVED);
        req.setRequestedLevel(level);
        req.setDecisionNote(note);
        req.setDecidedBy(userId);
        req.setDecidedAt(LocalDateTime.now());
        return requests.save(req);
    }

    /** Best-effort process termination — the request's own status remains the source of truth. */
    private void deleteProcess(AccessRequest req, String reason) {
        if (req.getProcessInstanceId() == null) return;
        try {
            runtimeService.deleteProcessInstance(req.getProcessInstanceId(), reason);
        } catch (RuntimeException e) {
            log.warn("Could not delete process {} for request {}: {}",
                    req.getProcessInstanceId(), req.getId(), e.getMessage());
        }
    }

    /** Re-read after the engine's delegates have written the row. */
    private AccessRequest reload(AccessRequest req) {
        return requests.findById(req.getId()).orElse(req);
    }

    /* ── authorization + helpers ────────────────────────────── */

    /**
     * May {@code userId} decide requests for {@code capabilityCode}? A platform operator, a
     * tenant owner, or — the point of delegated ownership — a resource owner of that capability.
     * Pass a null code to assert only the tenant-owner/operator part.
     */
    private void requireApprover(String userId, String tenantId, String capabilityCode) {
        if (isTenantAdmin(userId, tenantId)) return;
        if (capabilityCode != null
                && CapabilityOwnership.isOwner(identityService, userId, tenantId, capabilityCode)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Requires org owner, or resource owner of this capability");
    }

    /**
     * Platform operator, or scoped owner OF THIS tenant — not the global tenant-admin role, which
     * would let an admin of any org approve access here. See TenantOwnership.
     */
    private boolean isTenantAdmin(String userId, String tenantId) {
        List<Group> groups = identityService.createGroupQuery().groupMember(userId).list();
        List<String> tenants = identityService.createTenantQuery().userMember(userId).list()
                .stream().map(t -> t.getId()).toList();
        boolean operator = groups.stream().anyMatch(g -> CAMUNDA_ADMIN_GROUP.equals(g.getId()))
                || tenants.contains(parentClusterId);
        if (operator) return true;
        if (!tenants.contains(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of tenant '" + tenantId + "'");
        }
        return com.luke.engine.tenant.TenantOwnership.isOwner(identityService, userId, tenantId);
    }

    private AccessRequest load(String tenantId, String id) {
        return requests.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown access request: " + id));
    }

    /** Load a request that must belong to the caller. */
    private AccessRequest ownRequest(String tenantId, String userId, String id) {
        AccessRequest req = load(tenantId, id);
        if (!userId.equals(req.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your request");
        }
        return req;
    }

    /** Read-time enrichment: fill requesterName / decidedByName / capabilityName on one request. */
    private AccessRequest withNames(AccessRequest req) {
        withNames(List.of(req));
        return req;
    }

    /** Batch read-time enrichment across requests (one user-directory lookup, cached catalog reads). */
    private List<AccessRequest> withNames(List<AccessRequest> list) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (AccessRequest r : list) {
            if (r.getUserId() != null) ids.add(r.getUserId());
            if (r.getDecidedBy() != null) ids.add(r.getDecidedBy());
        }
        Map<String, String> names = userDirectory.namesFor(ids);
        Map<String, String> capNames = new java.util.HashMap<>();
        for (AccessRequest r : list) {
            if (r.getUserId() != null) r.setRequesterName(names.getOrDefault(r.getUserId(), r.getUserId()));
            if (r.getDecidedBy() != null) r.setDecidedByName(names.getOrDefault(r.getDecidedBy(), r.getDecidedBy()));
            String code = r.getCapabilityCode();
            r.setCapabilityName(capNames.computeIfAbsent(code,
                    c -> capabilities.findByCode(c).map(Capability::getName).orElse(c)));
        }
        return list;
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-User-Id is required");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
