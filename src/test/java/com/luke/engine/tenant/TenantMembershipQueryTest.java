package com.luke.engine.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Does {@code UserQuery.userId(u).memberOfTenant(t).count()} have the same trap as
 * {@code UserQuery.userId(u).memberOfGroup(g).count()} (which CIBSeven evaluates as "does user u
 * exist", ignoring the filter, returning 1 for everyone)? {@code OrgAdminController.requireTenantMember}
 * uses the memberOfTenant form to check the TARGET user is in the org; if it's broken, that guard is
 * inert. This asserts the correct answer for a non-member (0) — a failure means the same bug and a fix.
 */
@SpringBootTest
class TenantMembershipQueryTest {

    @Autowired
    private IdentityService identity;

    private static final String A = "TEN-MEM-A";
    private static final String B = "TEN-MEM-B";
    private static final String USER = "mem-test-user";

    @AfterEach
    void cleanup() {
        try { identity.deleteTenantUserMembership(A, USER); } catch (RuntimeException ignore) { }
        try { identity.deleteUser(USER); } catch (RuntimeException ignore) { }
        for (String t : new String[] {A, B}) {
            try { identity.deleteTenant(t); } catch (RuntimeException ignore) { }
        }
    }

    @Test
    void memberOfTenantMustNotReturnTrueForNonMembers() {
        for (String t : new String[] {A, B}) {
            var tn = identity.newTenant(t);
            tn.setName(t);
            identity.saveTenant(tn);
        }
        var u = identity.newUser(USER);
        u.setPassword("x");
        identity.saveUser(u);
        identity.createTenantUserMembership(A, USER); // USER is in A only

        // RELIABLE form (TenantQuery) — what requireTenantMember now uses: correct for both cases.
        assertThat(identity.createTenantQuery().tenantId(A).userMember(USER).count())
                .as("member of A").isEqualTo(1);
        assertThat(identity.createTenantQuery().tenantId(B).userMember(USER).count())
                .as("NOT a member of B").isZero();

        // CHARACTERIZATION of the BROKEN form (do NOT use it): userId+memberOfTenant ignores the
        // tenant filter and returns 1 for any existing user — 1 even for tenant B where USER is not a
        // member. This is why requireTenantMember was switched off it. If CIBSeven ever fixes this,
        // update this test.
        assertThat(identity.createUserQuery().userId(USER).memberOfTenant(A).count()).isEqualTo(1);
        assertThat(identity.createUserQuery().userId(USER).memberOfTenant(B).count())
                .as("BROKEN form returns 1 even for a non-member").isEqualTo(1);
    }
}
