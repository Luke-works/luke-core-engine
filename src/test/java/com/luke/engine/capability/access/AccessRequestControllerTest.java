package com.luke.engine.capability.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.capability.Capability;
import com.luke.engine.capability.capability.CapabilityRepository;
import com.luke.engine.tenant.UserDirectory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.GroupQuery;
import org.cibseven.bpm.engine.identity.Tenant;
import org.cibseven.bpm.engine.identity.TenantQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link AccessRequestController}: the duplicate-PENDING guard on
 * create, and the approve path actually granting access through the existing
 * {@link CapabilityGrantController#setGrant}. Repos / identity are mocked.
 */
class AccessRequestControllerTest {

    private static final String TENANT = "TEN-ACM-01JAN26";
    private static final String MEMBER = "workos:user_member";
    private static final String OWNER = "workos:user_owner";
    private static final String CODE = "FORMS";

    private AccessRequestRepository requests;
    private CapabilityGrantRepository grants;
    private CapabilityGrantController grantController;
    private CapabilityAccessService access;
    private CapabilityRepository capabilities;
    private IdentityService identityService;
    private AccessRequestController controller;

    @BeforeEach
    void setUp() {
        requests = mock(AccessRequestRepository.class);
        grants = mock(CapabilityGrantRepository.class);
        grantController = mock(CapabilityGrantController.class);
        access = mock(CapabilityAccessService.class);
        capabilities = mock(CapabilityRepository.class);
        UserDirectory userDirectory = mock(UserDirectory.class);
        identityService = mock(IdentityService.class);

        controller = new AccessRequestController(requests, grants, grantController, access,
                capabilities, userDirectory, identityService);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "parentClusterId", "parent_cluster");

        when(userDirectory.namesFor(any())).thenReturn(Map.of());
        when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /* ── create: duplicate-PENDING guard ────────────────────── */

    @Test
    void createRejectsDuplicatePending() {
        when(capabilities.findByCode(CODE)).thenReturn(Optional.of(new Capability()));
        when(access.tenantHasCapability(TENANT, CODE)).thenReturn(true);
        when(grants.findByTenantIdAndUserIdAndCapabilityCode(TENANT, MEMBER, CODE)).thenReturn(Optional.empty());
        when(requests.existsByTenantIdAndUserIdAndCapabilityCodeAndStatus(
                TENANT, MEMBER, CODE, AccessRequest.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> controller.create(TENANT, MEMBER,
                new AccessRequestController.CreateRequest(CODE, "read", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("request already pending")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(requests, times(0)).save(any());
    }

    @Test
    void createSucceedsWhenNoPendingExists() {
        when(capabilities.findByCode(CODE)).thenReturn(Optional.of(new Capability()));
        when(access.tenantHasCapability(TENANT, CODE)).thenReturn(true);
        when(grants.findByTenantIdAndUserIdAndCapabilityCode(TENANT, MEMBER, CODE)).thenReturn(Optional.empty());
        when(requests.existsByTenantIdAndUserIdAndCapabilityCodeAndStatus(
                TENANT, MEMBER, CODE, AccessRequest.PENDING)).thenReturn(false);

        AccessRequest created = controller.create(TENANT, MEMBER,
                new AccessRequestController.CreateRequest(CODE, "read-write", "need it"));

        assertThat(created.getStatus()).isEqualTo(AccessRequest.PENDING);
        assertThat(created.getUserId()).isEqualTo(MEMBER);
        assertThat(created.getCapabilityCode()).isEqualTo(CODE);
        assertThat(created.getRequestedLevel()).isEqualTo("read-write");
        verify(requests, times(1)).save(any());
    }

    /* ── approve: grants access ─────────────────────────────── */

    @Test
    void approveGrantsAccessAndMarksApproved() {
        adminCaller(OWNER);
        AccessRequest pending = new AccessRequest(TENANT, MEMBER, CODE, "read");
        pending.setId("req-1");
        when(requests.findByIdAndTenantId("req-1", TENANT)).thenReturn(Optional.of(pending));

        AccessRequest result = controller.approve(TENANT, OWNER, "req-1",
                new AccessRequestController.ApproveBody(null));

        // The grant was actually made at the requested level via the existing grant path.
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
        pending.setId("req-2");
        when(requests.findByIdAndTenantId("req-2", TENANT)).thenReturn(Optional.of(pending));

        AccessRequest result = controller.approve(TENANT, OWNER, "req-2",
                new AccessRequestController.ApproveBody("read-write"));

        verify(grantController, times(1)).setGrant(eq(TENANT), eq(MEMBER), eq(CODE), eq(OWNER),
                eq(new CapabilityGrantController.GrantBody("read-write")));
        assertThat(result.getRequestedLevel()).isEqualTo("read-write");
    }

    @Test
    void approveRejectsNonPending() {
        adminCaller(OWNER);
        AccessRequest decided = new AccessRequest(TENANT, MEMBER, CODE, "read");
        decided.setId("req-3");
        decided.setStatus(AccessRequest.DENIED);
        when(requests.findByIdAndTenantId("req-3", TENANT)).thenReturn(Optional.of(decided));

        assertThatThrownBy(() -> controller.approve(TENANT, OWNER, "req-3",
                new AccessRequestController.ApproveBody(null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(grantController, times(0)).setGrant(any(), any(), any(), any(), any());
    }

    /* ── cross-tenant escalation: admin of another org is NOT an owner here ── */

    @Test
    void approveRefusedForAdminOfAnotherTenant() {
        // The exploit: caller owns a DIFFERENT org and is only a plain member of TENANT.
        // Ownership is scoped, so they must be refused here (was previously allowed via the
        // global tenant-admin role).
        ownerCaller(OWNER, "TEN-OTHER-09SEP26");
        assertThatThrownBy(() -> controller.approve(TENANT, OWNER, "req-x",
                new AccessRequestController.ApproveBody(null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(grantController, times(0)).setGrant(any(), any(), any(), any(), any());
    }

    /* ── helpers: make {@code userId} an owner of a tenant, member of TENANT ── */

    /** Caller is an owner of TENANT (the common case). */
    private void adminCaller(String userId) {
        ownerCaller(userId, TENANT);
    }

    /**
     * Caller is a plain member of TENANT and an owner of {@code ownedTenant} only. When
     * {@code ownedTenant != TENANT} this models the cross-tenant-escalation attacker.
     */
    private void ownerCaller(String userId, String ownedTenant) {
        // A member of TENANT (so the membership gate passes).
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(TENANT);
        TenantQuery tenantQuery = mock(TenantQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(tenantQuery.list()).thenReturn(List.of(tenant));
        when(identityService.createTenantQuery()).thenReturn(tenantQuery);

        // createGroupQuery() serves BOTH the operator check (.groupMember(u).list() → no camunda-admin)
        // and TenantOwnership.isOwner (.groupId("owner:<t>").groupMember(u).count()). Ownership resolves
        // to 1 only for ownedTenant. (Mirrors the real group-query path — the userId+memberOfGroup form
        // was the broken one that shipped the escalation.)
        GroupQuery ownerHit = mock(GroupQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(ownerHit.count()).thenReturn(1L);
        GroupQuery ownerMiss = mock(GroupQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(ownerMiss.count()).thenReturn(0L);
        GroupQuery groupQuery = mock(GroupQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(groupQuery.list()).thenReturn(List.of()); // operator check: no camunda-admin
        when(groupQuery.groupId("owner:" + ownedTenant)).thenReturn(ownerHit);
        when(groupQuery.groupId(org.mockito.ArgumentMatchers.argThat(
                g -> !("owner:" + ownedTenant).equals(g)))).thenReturn(ownerMiss);
        when(identityService.createGroupQuery()).thenReturn(groupQuery);
    }
}
