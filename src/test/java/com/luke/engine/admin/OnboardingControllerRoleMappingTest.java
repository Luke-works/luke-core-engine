package com.luke.engine.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.audit.AdminAuditService;
import com.luke.engine.config.ApiCallerResolver;
import java.util.List;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.finos.fluxnova.bpm.engine.identity.GroupQuery;
import org.finos.fluxnova.bpm.engine.identity.TenantQuery;
import org.finos.fluxnova.bpm.engine.identity.User;
import org.finos.fluxnova.bpm.engine.identity.UserQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * #61: the onboarding endpoint normalizes an incoming WorkOS role slug through
 * {@link com.luke.engine.config.RoleCatalog#fromWorkosSlug} before provisioning, so an
 * IdP-assigned role — including WorkOS's built-in {@code member}/{@code admin} — provisions the
 * right engine role group, while an unknown role still fails closed (400). The slug→role map
 * itself is unit-tested in {@code RoleCatalogTest}; this pins the wiring end-to-end through
 * {@link OnboardingController#onboard}.
 */
class OnboardingControllerRoleMappingTest {

    private static final String TENANT = "t-onb";
    private static final String USER = "u-onb";
    private static final String OPERATOR_AUTH = "Basic operator";

    private IdentityService identity;
    private OnboardingController controller;

    @BeforeEach
    void setUp() {
        identity = mock(IdentityService.class);

        // Operator privilege: createGroupQuery().groupMember("operator").list() → [camunda-admin].
        Group adminGroup = mock(Group.class);
        when(adminGroup.getId()).thenReturn("camunda-admin");
        GroupQuery operatorGroups = mock(GroupQuery.class, RETURNS_SELF);
        when(operatorGroups.list()).thenReturn(List.of(adminGroup));
        when(operatorGroups.count()).thenReturn(1L);

        // A group that EXISTS (count=1); its member check returns 0 (user not yet a member).
        GroupQuery notMember = mock(GroupQuery.class, RETURNS_SELF);
        when(notMember.count()).thenReturn(0L);
        GroupQuery exists = mock(GroupQuery.class, RETURNS_SELF);
        when(exists.count()).thenReturn(1L);
        when(exists.groupMember(anyString())).thenReturn(notMember);

        // Any un-stubbed group → count 0, which drives the "Unknown role" 400.
        GroupQuery miss = mock(GroupQuery.class, RETURNS_SELF);
        when(miss.count()).thenReturn(0L);

        GroupQuery base = mock(GroupQuery.class, RETURNS_SELF);
        when(base.groupMember(anyString())).thenReturn(operatorGroups); // isPrivileged: caller is camunda-admin
        when(base.groupId(anyString())).thenReturn(miss);              // default: unknown role
        when(base.groupId("tenant-user")).thenReturn(exists);
        when(base.groupId("tenant-admin")).thenReturn(exists);
        when(base.groupId("process-operator")).thenReturn(exists);
        when(base.groupId("task-worker")).thenReturn(exists);
        when(base.groupId("owner:" + TENANT)).thenReturn(exists);      // ownership group already exists
        when(identity.createGroupQuery()).thenReturn(base);

        // Tenant: known (count=1); user not yet a member (count=0).
        TenantQuery notTenantMember = mock(TenantQuery.class, RETURNS_SELF);
        when(notTenantMember.count()).thenReturn(0L);
        TenantQuery tenantQuery = mock(TenantQuery.class, RETURNS_SELF);
        when(tenantQuery.count()).thenReturn(1L);
        when(tenantQuery.userMember(anyString())).thenReturn(notTenantMember);
        when(identity.createTenantQuery()).thenReturn(tenantQuery);

        // User not present yet (count=0) → created; also backs isMemberOfOwnerGroup's memberOfGroup().count().
        UserQuery userQuery = mock(UserQuery.class, RETURNS_SELF);
        when(userQuery.count()).thenReturn(0L);
        when(identity.createUserQuery()).thenReturn(userQuery);
        when(identity.newUser(anyString())).thenReturn(mock(User.class));

        ApiCallerResolver callers = mock(ApiCallerResolver.class);
        when(callers.basicUsername(any())).thenReturn("operator");

        controller = new OnboardingController(
                identity, mock(UserDeprovisioningService.class), mock(AdminAuditService.class), callers);
        // @Value field isn't injected in a constructor-only unit test; set it so the parent-cluster
        // fallback in isPrivileged() doesn't build a bound method reference on null.
        ReflectionTestUtils.setField(controller, "parentClusterId", "parent_cluster");
    }

    private ResponseEntity<?> onboard(String role) {
        return controller.onboard(OPERATOR_AUTH, new OnboardingController.OnboardUserRequest(
                USER, "Jo", "Blow", "jo@acme.com", "pw", TENANT, role, "READ_WRITE"));
    }

    @Test
    void workosMemberProvisionsTenantUser() {
        assertThat(onboard("member").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(identity).createMembership(USER, "tenant-user");
    }

    @Test
    void workosAdminProvisionsTenantAdminAndOwnership() {
        assertThat(onboard("admin").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(identity).createMembership(USER, "tenant-admin");
        verify(identity).createMembership(USER, "owner:" + TENANT); // tenant-admin ⇒ tenant owner
    }

    @Test
    void mirrorSlugPassesThroughUnchanged() {
        assertThat(onboard("process-operator").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(identity).createMembership(USER, "process-operator");
    }

    @Test
    void unknownRoleFailsClosed() {
        assertThat(onboard("wizard").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(identity, never()).createMembership(anyString(), anyString());
    }
}
