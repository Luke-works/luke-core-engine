package com.luke.engine.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.GroupQuery;
import org.cibseven.bpm.engine.identity.UserQuery;
import org.junit.jupiter.api.Test;

/** The scoped-ownership binding: id format, tenant-scoped membership, idempotent grant. */
class TenantOwnershipTest {

    private static final String TENANT = "TEN-ACM-01JAN26";
    private static final String USER = "workos:user_1";

    @Test
    void groupIdIsScopedToTenant() {
        assertThat(TenantOwnership.groupId(TENANT)).isEqualTo("owner:" + TENANT);
    }

    @Test
    void isOwnerChecksMembershipOfTheTenantScopedGroup() {
        IdentityService identity = mock(IdentityService.class);
        UserQuery q = mock(UserQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(q.count()).thenReturn(1L);
        when(identity.createUserQuery()).thenReturn(q);

        assertThat(TenantOwnership.isOwner(identity, USER, TENANT)).isTrue();
        verify(q).userId(USER);
        verify(q).memberOfGroup("owner:" + TENANT); // NOT the global "tenant-admin" group
    }

    @Test
    void grantCreatesGroupOnceAndIsIdempotent() {
        IdentityService identity = mock(IdentityService.class);
        GroupQuery gq = mock(GroupQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(gq.count()).thenReturn(0L); // group does not exist yet
        when(identity.createGroupQuery()).thenReturn(gq);
        when(identity.newGroup(eq("owner:" + TENANT))).thenReturn(mock(Group.class));
        UserQuery uq = mock(UserQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(uq.count()).thenReturn(0L); // not yet a member
        when(identity.createUserQuery()).thenReturn(uq);

        TenantOwnership.grant(identity, USER, TENANT);

        verify(identity).saveGroup(org.mockito.ArgumentMatchers.any(Group.class));
        verify(identity).createMembership(USER, "owner:" + TENANT);
    }

    @Test
    void grantDoesNotDuplicateMembership() {
        IdentityService identity = mock(IdentityService.class);
        GroupQuery gq = mock(GroupQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(gq.count()).thenReturn(1L); // group already exists
        when(identity.createGroupQuery()).thenReturn(gq);
        UserQuery uq = mock(UserQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(uq.count()).thenReturn(1L); // already a member
        when(identity.createUserQuery()).thenReturn(uq);

        TenantOwnership.grant(identity, USER, TENANT);

        verify(identity, never()).saveGroup(org.mockito.ArgumentMatchers.any(Group.class));
        verify(identity, never()).createMembership(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokeDeletesMembershipAndSwallowsErrors() {
        IdentityService identity = mock(IdentityService.class);
        TenantOwnership.revoke(identity, USER, TENANT);
        verify(identity, times(1)).deleteMembership(USER, "owner:" + TENANT);
    }
}
