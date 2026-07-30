package com.luke.engine.capability.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.capability.Capability;
import com.luke.engine.capability.capability.CapabilityRepository;
import com.luke.engine.tenant.UserDirectory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.TaskService;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.finos.fluxnova.bpm.engine.identity.Tenant;
import org.finos.fluxnova.bpm.engine.identity.TenantQuery;
import org.finos.fluxnova.bpm.engine.identity.GroupQuery;
import org.finos.fluxnova.bpm.engine.task.Task;
import org.finos.fluxnova.bpm.engine.task.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link AccessRequestController}: the duplicate-open guard on create, the
 * Camunda-orchestrated approve/deny (completing the owner's task rather than granting inline),
 * the requester's return path, delegated resource-owner authorization, and the legacy fallback
 * when no process instance exists. Repos / identity / engine services are mocked.
 */
class AccessRequestControllerTest {

    private static final String TENANT = "TEN-ACM-01JAN26";
    private static final String MEMBER = "workos:user_member";
    private static final String OWNER = "workos:user_owner";
    private static final String CODE = "FORMS";
    private static final String PID = "proc-1";

    private AccessRequestRepository requests;
    private AccessRequestOutboxRepository outbox;
    private CapabilityGrantRepository grants;
    private CapabilityGrantController grantController;
    private CapabilityAccessService access;
    private CapabilityRepository capabilities;
    private IdentityService identityService;
    private TaskService taskService;
    private RuntimeService runtimeService;
    private AccessRequestController controller;

    @BeforeEach
    void setUp() {
        requests = mock(AccessRequestRepository.class);
        outbox = mock(AccessRequestOutboxRepository.class);
        grants = mock(CapabilityGrantRepository.class);
        grantController = mock(CapabilityGrantController.class);
        access = mock(CapabilityAccessService.class);
        capabilities = mock(CapabilityRepository.class);
        UserDirectory userDirectory = mock(UserDirectory.class);
        identityService = mock(IdentityService.class);
        taskService = mock(TaskService.class);
        runtimeService = mock(RuntimeService.class);

        controller = new AccessRequestController(requests, outbox, grants, grantController, access,
                capabilities, userDirectory, identityService, taskService, runtimeService);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "parentClusterId", "parent_cluster");

        when(userDirectory.namesFor(any())).thenReturn(Map.of());
        when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outbox.save(any())).thenAnswer(inv -> inv.getArgument(0));
        noTask(); // default: no live process task → legacy path
    }

    /* ── create ─────────────────────────────────────────────── */

    @Test
    void createRejectsDuplicateOpenRequest() {
        requestable();
        when(requests.existsByTenantIdAndUserIdAndCapabilityCodeAndStatus(
                TENANT, MEMBER, CODE, AccessRequest.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> controller.create(TENANT, MEMBER,
                new AccessRequestController.CreateRequest(CODE, "read", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("request already open")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(requests, never()).save(any());
    }

    /** A request already sitting with the requester after a rejection is still open. */
    @Test
    void createRejectsWhenARequestIsAlreadyReturnedToTheUser() {
        requestable();
        when(requests.existsByTenantIdAndUserIdAndCapabilityCodeAndStatus(
                TENANT, MEMBER, CODE, AccessRequest.RETURNED)).thenReturn(true);

        assertThatThrownBy(() -> controller.create(TENANT, MEMBER,
                new AccessRequestController.CreateRequest(CODE, "read", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createQueuesTheApprovalProcessInTheSameTransaction() {
        requestable();

        AccessRequest created = controller.create(TENANT, MEMBER,
                new AccessRequestController.CreateRequest(CODE, "read-write", "need it"));

        assertThat(created.getStatus()).isEqualTo(AccessRequest.PENDING);
        assertThat(created.getRequestedLevel()).isEqualTo("read-write");
        verify(requests, times(1)).save(any());
        // The outbox row is what guarantees the request reaches an approver.
        ArgumentCaptor<AccessRequestOutbox> row = ArgumentCaptor.forClass(AccessRequestOutbox.class);
        verify(outbox, times(1)).save(row.capture());
        assertThat(row.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(row.getValue().getStatus()).isEqualTo(AccessRequestOutbox.QUEUED);
        assertThat(row.getValue().getBusinessKey()).contains(created.getId() == null ? "" : created.getId());
    }

    /** The rank fix: holding `read` must not block asking for `contributor`. */
    @Test
    void createAllowsAnUpgradeThroughTheMiddleLevel() {
        requestable();
        CapabilityGrant held = new CapabilityGrant();
        held.setLevel(CapabilityLevel.READ);
        when(grants.findByTenantIdAndUserIdAndCapabilityCode(TENANT, MEMBER, CODE))
                .thenReturn(Optional.of(held));

        AccessRequest created = controller.create(TENANT, MEMBER,
                new AccessRequestController.CreateRequest(CODE, CapabilityLevel.CONTRIBUTOR, null));

        assertThat(created.getRequestedLevel()).isEqualTo(CapabilityLevel.CONTRIBUTOR);
    }

    @Test
    void createStillRejectsWhatTheCallerAlreadyHas() {
        requestable();
        CapabilityGrant held = new CapabilityGrant();
        held.setLevel(CapabilityLevel.READ_WRITE);
        when(grants.findByTenantIdAndUserIdAndCapabilityCode(TENANT, MEMBER, CODE))
                .thenReturn(Optional.of(held));

        assertThatThrownBy(() -> controller.create(TENANT, MEMBER,
                new AccessRequestController.CreateRequest(CODE, CapabilityLevel.CONTRIBUTOR, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already have this access");
    }

    /* ── approve / deny via the process ─────────────────────── */

    @Test
    void approveCompletesTheOwnerTaskAndLetsTheProcessProvision() {
        adminCaller(OWNER);
        AccessRequest pending = orchestratedRequest("req-1");
        Task task = liveTask("task-1");

        controller.approve(TENANT, OWNER, "req-1", new AccessRequestController.ApproveBody("read-write", "ok"));

        // The controller must NOT grant inline — the provisioning delegate owns that.
        verify(grantController, never()).setGrant(any(), any(), any(), any(), any());
        verify(taskService).setAssignee(task.getId(), OWNER);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(taskService).complete(eq("task-1"), vars.capture());
        assertThat(vars.getValue()).containsEntry(AccessProcessVariables.APPROVED, true);
        assertThat(vars.getValue()).containsEntry(AccessProcessVariables.GRANT_LEVEL, "read-write");
        assertThat(vars.getValue()).containsEntry(AccessProcessVariables.DECIDED_BY, OWNER);
        assertThat(pending).isNotNull();
    }

    @Test
    void denyCompletesTheTaskWithARejection() {
        adminCaller(OWNER);
        orchestratedRequest("req-2");
        liveTask("task-2");

        controller.deny(TENANT, OWNER, "req-2", new AccessRequestController.DenyBody("too broad"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(taskService).complete(eq("task-2"), vars.capture());
        assertThat(vars.getValue()).containsEntry(AccessProcessVariables.APPROVED, false);
        assertThat(vars.getValue()).containsEntry(AccessProcessVariables.DECISION_NOTE, "too broad");
    }

    /* ── legacy fallback (no process instance) ──────────────── */

    @Test
    void approveGrantsDirectlyWhenThereIsNoProcess() {
        adminCaller(OWNER);
        AccessRequest pending = new AccessRequest(TENANT, MEMBER, CODE, "read");
        pending.setId("req-3");
        when(requests.findByIdAndTenantId("req-3", TENANT)).thenReturn(Optional.of(pending));

        AccessRequest result = controller.approve(TENANT, OWNER, "req-3",
                new AccessRequestController.ApproveBody(null, null));

        verify(grantController, times(1)).setGrant(eq(TENANT), eq(MEMBER), eq(CODE), eq(OWNER),
                eq(new CapabilityGrantController.GrantBody("read")));
        assertThat(result.getStatus()).isEqualTo(AccessRequest.APPROVED);
        assertThat(result.getDecidedBy()).isEqualTo(OWNER);
        assertThat(result.getDecidedAt()).isNotNull();
    }

    @Test
    void approveHonoursLevelOverride() {
        adminCaller(OWNER);
        AccessRequest pending = new AccessRequest(TENANT, MEMBER, CODE, "read");
        pending.setId("req-4");
        when(requests.findByIdAndTenantId("req-4", TENANT)).thenReturn(Optional.of(pending));

        AccessRequest result = controller.approve(TENANT, OWNER, "req-4",
                new AccessRequestController.ApproveBody("read-write", null));

        verify(grantController, times(1)).setGrant(eq(TENANT), eq(MEMBER), eq(CODE), eq(OWNER),
                eq(new CapabilityGrantController.GrantBody("read-write")));
        assertThat(result.getRequestedLevel()).isEqualTo("read-write");
    }

    @Test
    void approveRejectsNonPending() {
        adminCaller(OWNER);
        AccessRequest decided = new AccessRequest(TENANT, MEMBER, CODE, "read");
        decided.setId("req-5");
        decided.setStatus(AccessRequest.DENIED);
        when(requests.findByIdAndTenantId("req-5", TENANT)).thenReturn(Optional.of(decided));

        assertThatThrownBy(() -> controller.approve(TENANT, OWNER, "req-5",
                new AccessRequestController.ApproveBody(null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(grantController, never()).setGrant(any(), any(), any(), any(), any());
    }

    /* ── the requester's return path ────────────────────────── */

    @Test
    void resubmitCompletesTheReworkTaskWithTheRevisedLevel() {
        AccessRequest returned = orchestratedRequest("req-6");
        returned.setStatus(AccessRequest.RETURNED);
        liveTask("task-6");

        controller.resubmit(TENANT, MEMBER, "req-6",
                new AccessRequestController.ResubmitBody("read", "smaller ask this time"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(taskService).complete(eq("task-6"), vars.capture());
        assertThat(vars.getValue()).containsEntry(AccessProcessVariables.RESUBMIT, true);
        assertThat(vars.getValue()).containsEntry(AccessProcessVariables.REQUESTED_LEVEL, "read");
        assertThat(returned.getNote()).isEqualTo("smaller ask this time");
    }

    @Test
    void withdrawCompletesTheReworkTaskWithARefusal() {
        AccessRequest returned = orchestratedRequest("req-7");
        returned.setStatus(AccessRequest.RETURNED);
        liveTask("task-7");

        controller.withdraw(TENANT, MEMBER, "req-7");

        verify(taskService).complete("task-7", Map.of(AccessProcessVariables.RESUBMIT, false));
    }

    @Test
    void onlyAReturnedRequestCanBeResubmitted() {
        AccessRequest pending = orchestratedRequest("req-8"); // still PENDING
        assertThat(pending.getStatus()).isEqualTo(AccessRequest.PENDING);

        assertThatThrownBy(() -> controller.resubmit(TENANT, MEMBER, "req-8", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anotherMemberCannotResubmitYourRequest() {
        AccessRequest returned = orchestratedRequest("req-9");
        returned.setStatus(AccessRequest.RETURNED);

        assertThatThrownBy(() -> controller.resubmit(TENANT, "workos:user_someone_else", "req-9", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /* ── delegated resource-owner authorization ─────────────── */

    @Test
    void aResourceOwnerOfTheCapabilityMayApproveItWithoutBeingAnOrgOwner() {
        String resourceOwner = "workos:user_forms_owner";
        plainMemberOwningCapability(resourceOwner, CODE);
        AccessRequest pending = new AccessRequest(TENANT, MEMBER, CODE, "read");
        pending.setId("req-10");
        when(requests.findByIdAndTenantId("req-10", TENANT)).thenReturn(Optional.of(pending));

        AccessRequest result = controller.approve(TENANT, resourceOwner, "req-10",
                new AccessRequestController.ApproveBody(null, null));

        assertThat(result.getStatus()).isEqualTo(AccessRequest.APPROVED);
    }

    @Test
    void aResourceOwnerOfAnotherCapabilityIsRefused() {
        String emailOwner = "workos:user_email_owner";
        plainMemberOwningCapability(emailOwner, "EMAIL"); // owns EMAIL, not FORMS
        AccessRequest pending = new AccessRequest(TENANT, MEMBER, CODE, "read");
        pending.setId("req-11");
        when(requests.findByIdAndTenantId("req-11", TENANT)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> controller.approve(TENANT, emailOwner, "req-11",
                new AccessRequestController.ApproveBody(null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(grantController, never()).setGrant(any(), any(), any(), any(), any());
    }

    /* ── the approval queue ─────────────────────────────────── */

    @Test
    void aResourceOwnerSeesOnlyTheQueueForCapabilitiesTheyOwn() {
        String formsOwner = "workos:user_forms_owner";
        plainMemberOwningCapability(formsOwner, CODE);
        AccessRequest forms = new AccessRequest(TENANT, MEMBER, CODE, "read");
        AccessRequest email = new AccessRequest(TENANT, MEMBER, "EMAIL", "read");
        when(requests.findByTenantIdAndStatusOrderByRequestedAtDesc(TENANT, AccessRequest.PENDING))
                .thenReturn(List.of(forms, email));

        List<AccessRequest> queue = controller.orgRequests(TENANT, formsOwner, AccessRequest.PENDING);

        assertThat(queue).containsExactly(forms);
    }

    /** Authorized on OWNING something, not on the queue being non-empty. */
    @Test
    void aResourceOwnerWithAnEmptyQueueGetsAnEmptyListNotAnError() {
        String formsOwner = "workos:user_forms_owner";
        plainMemberOwningCapability(formsOwner, CODE);
        when(requests.findByTenantIdAndStatusOrderByRequestedAtDesc(TENANT, AccessRequest.PENDING))
                .thenReturn(List.of());

        assertThat(controller.orgRequests(TENANT, formsOwner, AccessRequest.PENDING)).isEmpty();
    }

    /** ...and a member who owns nothing is refused even when the queue is empty (no probing). */
    @Test
    void aPlainMemberCannotOpenTheQueueAtAll() {
        String nobody = "workos:user_nobody";
        memberOfTenant();
        // Build the query mock BEFORE the when(...) — groupQueryMatching stubs internally, and
        // nesting that inside another when() is an UnfinishedStubbing error.
        GroupQuery groupQuery = groupQueryMatching("owner:SOMETHING-ELSE");
        when(identityService.createGroupQuery()).thenReturn(groupQuery);
        when(requests.findByTenantIdAndStatusOrderByRequestedAtDesc(TENANT, AccessRequest.PENDING))
                .thenReturn(List.of());

        assertThatThrownBy(() -> controller.orgRequests(TENANT, nobody, AccessRequest.PENDING))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /* ── cross-tenant escalation: admin of another org is NOT an owner here ── */

    @Test
    void approveRefusedForAdminOfAnotherTenant() {
        // The exploit: caller owns a DIFFERENT org and is only a plain member of TENANT.
        // Ownership is scoped, so they must be refused here (was previously allowed via the
        // global tenant-admin role).
        ownerCaller(OWNER, "TEN-OTHER-09SEP26");
        AccessRequest pending = new AccessRequest(TENANT, MEMBER, CODE, "read");
        pending.setId("req-x");
        when(requests.findByIdAndTenantId("req-x", TENANT)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> controller.approve(TENANT, OWNER, "req-x",
                new AccessRequestController.ApproveBody(null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(grantController, never()).setGrant(any(), any(), any(), any(), any());
    }

    /* ── fixtures ───────────────────────────────────────────── */

    /** Capability exists, org is subscribed, caller holds nothing, no open request. */
    private void requestable() {
        when(capabilities.findByCode(CODE)).thenReturn(Optional.of(new Capability()));
        when(access.tenantHasCapability(TENANT, CODE)).thenReturn(true);
        when(grants.findByTenantIdAndUserIdAndCapabilityCode(TENANT, MEMBER, CODE)).thenReturn(Optional.empty());
        when(requests.existsByTenantIdAndUserIdAndCapabilityCodeAndStatus(any(), any(), any(), any()))
                .thenReturn(false);
    }

    /** A PENDING request already bound to a running process instance. */
    private AccessRequest orchestratedRequest(String id) {
        AccessRequest req = new AccessRequest(TENANT, MEMBER, CODE, "read");
        req.setId(id);
        req.setProcessInstanceId(PID);
        when(requests.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(req));
        when(requests.findById(id)).thenReturn(Optional.of(req));
        return req;
    }

    /** The process has an active task with this id. */
    private Task liveTask(String taskId) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        TaskQuery query = mock(TaskQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(query.singleResult()).thenReturn(task);
        when(taskService.createTaskQuery()).thenReturn(query);
        return task;
    }

    /** No active task anywhere (the legacy/degraded path). */
    private void noTask() {
        TaskQuery query = mock(TaskQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(query.singleResult()).thenReturn(null);
        when(taskService.createTaskQuery()).thenReturn(query);
    }

    /** Caller is an owner of TENANT (the common case). */
    private void adminCaller(String userId) {
        ownerCaller(userId, TENANT);
    }

    /**
     * Caller is a plain member of TENANT and an owner of {@code ownedTenant} only. When
     * {@code ownedTenant != TENANT} this models the cross-tenant-escalation attacker.
     */
    private void ownerCaller(String userId, String ownedTenant) {
        memberOfTenant();
        GroupQuery groupQuery = groupQueryMatching("owner:" + ownedTenant);
        when(identityService.createGroupQuery()).thenReturn(groupQuery);
    }

    /**
     * Caller is a plain member of TENANT — NOT an org owner — who is a resource owner of exactly
     * one capability. This is the delegated-ownership case.
     */
    private void plainMemberOwningCapability(String userId, String capabilityCode) {
        memberOfTenant();
        String capownerGroup = com.luke.engine.tenant.CapabilityOwnership.ownerGroupId(TENANT, capabilityCode);
        GroupQuery groupQuery = groupQueryMatching(capownerGroup);
        // CapabilityOwnership.ownsAny reads the caller's group LIST (not a per-group count), so the
        // membership has to show up there too — this is the same query the operator check uses.
        Group owned = mock(Group.class);
        when(owned.getId()).thenReturn(capownerGroup);
        when(groupQuery.list()).thenReturn(List.of(owned));
        when(identityService.createGroupQuery()).thenReturn(groupQuery);
    }

    private void memberOfTenant() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(TENANT);
        TenantQuery tenantQuery = mock(TenantQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(tenantQuery.list()).thenReturn(List.of(tenant));
        when(identityService.createTenantQuery()).thenReturn(tenantQuery);
    }

    /**
     * createGroupQuery() serves BOTH the operator check (.groupMember(u).list() → no camunda-admin)
     * and the ownership checks (.groupId(g).groupMember(u).count()). Only {@code hitGroupId}
     * resolves to a member. (Mirrors the real group-query path — the userId+memberOfGroup form was
     * the broken one that shipped the escalation.)
     */
    private GroupQuery groupQueryMatching(String hitGroupId) {
        GroupQuery hit = mock(GroupQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(hit.count()).thenReturn(1L);
        GroupQuery miss = mock(GroupQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(miss.count()).thenReturn(0L);
        GroupQuery groupQuery = mock(GroupQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(groupQuery.list()).thenReturn(List.of()); // operator check: no camunda-admin
        when(groupQuery.groupId(anyString())).thenReturn(miss);
        when(groupQuery.groupId(hitGroupId)).thenReturn(hit);
        return groupQuery;
    }
}
