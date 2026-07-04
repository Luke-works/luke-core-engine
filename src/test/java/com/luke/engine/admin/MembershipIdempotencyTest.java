package com.luke.engine.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cibseven.bpm.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Regression for the qa 500 found during the Suite-1 pentest: adding an existing user to a SECOND
 * tenant with a role they already hold 500'd, because engine role groups (tenant-user, …) are GLOBAL
 * and {@code OrgAdminController.createUser} called {@code createMembership} unguarded — a duplicate on
 * the shared group throws.
 *
 * <p>Against the real (H2) {@link IdentityService}, this documents the hazard (a second
 * {@code createMembership} on the same group throws) and proves the guard the fix uses
 * ({@code createGroupQuery().groupId(g).groupMember(u).count() > 0}) correctly detects the existing
 * membership so the second add is skipped instead of exploding.
 */
@SpringBootTest
class MembershipIdempotencyTest {

    @Autowired
    private IdentityService identity;

    private static final String USER = "membership-idem-user";
    private static final String GROUP = "tenant-user"; // a real global role group created at startup

    @AfterEach
    void cleanup() {
        try { identity.deleteMembership(USER, GROUP); } catch (RuntimeException ignore) { }
        try { identity.deleteUser(USER); } catch (RuntimeException ignore) { }
    }

    @Test
    void guardDetectsExistingGlobalRoleMembershipSoTheSecondAddIsSkipped() {
        var u = identity.newUser(USER);
        u.setPassword("x");
        identity.saveUser(u);

        // First assignment (tenant A): user joins the global role group.
        identity.createMembership(USER, GROUP);

        // The guard the fix uses must see the membership — so createUser skips the duplicate add.
        boolean alreadyMember = identity.createGroupQuery().groupId(GROUP).groupMember(USER).count() > 0;
        assertThat(alreadyMember).isTrue();

        // And this is exactly why the guard is needed: an unguarded second add (tenant B, same role)
        // throws — the 500 the pentest saw on qa.
        assertThatThrownBy(() -> identity.createMembership(USER, GROUP)).isInstanceOf(RuntimeException.class);
    }
}
