package com.luke.engine.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.finos.fluxnova.bpm.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * INTEGRATION test against the real (H2) {@link IdentityService} — like {@link TenantOwnershipTest}
 * and {@link CandidateGroupOwnershipTest}, deliberately not mocked, so an identity-query bug can't
 * hide (the same trap that once made the tenant-ownership check inert).
 *
 * <p>The fallback assertions matter most: {@code approverGroupId} decides where an access-request
 * approval task lands, so "no resource owners → tenant owners" has to hold against the real
 * queries, or requests would route to an empty group and strand.
 */
@SpringBootTest
class CapabilityOwnershipTest {

    @Autowired
    private IdentityService identity;

    private static final String TENANT = "TEN-CAPOWN-TEST";
    private static final String CODE = "FORMS";
    private static final String OTHER_CODE = "EMAIL";
    private static final String OWNER = "capown-test-owner";
    private static final String PLAIN = "capown-test-plain";

    @AfterEach
    void cleanup() {
        for (String code : new String[] {CODE, OTHER_CODE}) {
            for (String u : new String[] {OWNER, PLAIN}) {
                try {
                    identity.deleteMembership(u, CapabilityOwnership.ownerGroupId(TENANT, code));
                } catch (RuntimeException ignore) { /* not a member */ }
            }
            try {
                identity.deleteGroup(CapabilityOwnership.ownerGroupId(TENANT, code));
            } catch (RuntimeException ignore) { /* never created */ }
        }
        for (String u : new String[] {OWNER, PLAIN}) {
            try { identity.deleteUser(u); } catch (RuntimeException ignore) { /* never created */ }
        }
    }

    private void createUser(String id) {
        var u = identity.newUser(id);
        u.setPassword("x");
        identity.saveUser(u);
    }

    @Test
    void aPlainMemberIsNotAResourceOwner() {
        createUser(PLAIN);
        assertThat(CapabilityOwnership.isOwner(identity, PLAIN, TENANT, CODE)).isFalse();
    }

    @Test
    void grantAndRevokeMoveMembership() {
        createUser(OWNER);

        CapabilityOwnership.grant(identity, OWNER, TENANT, CODE);
        assertThat(CapabilityOwnership.isOwner(identity, OWNER, TENANT, CODE)).isTrue();
        assertThat(CapabilityOwnership.ownerIds(identity, TENANT, CODE)).containsExactly(OWNER);

        // Idempotent: granting twice must not blow up or duplicate.
        CapabilityOwnership.grant(identity, OWNER, TENANT, CODE);
        assertThat(CapabilityOwnership.ownerIds(identity, TENANT, CODE)).containsExactly(OWNER);

        CapabilityOwnership.revoke(identity, OWNER, TENANT, CODE);
        assertThat(CapabilityOwnership.isOwner(identity, OWNER, TENANT, CODE)).isFalse();
    }

    @Test
    void ownershipIsScopedToOneCapability() {
        createUser(OWNER);
        CapabilityOwnership.grant(identity, OWNER, TENANT, CODE);

        assertThat(CapabilityOwnership.isOwner(identity, OWNER, TENANT, CODE)).isTrue();
        assertThat(CapabilityOwnership.isOwner(identity, OWNER, TENANT, OTHER_CODE)).isFalse();
    }

    @Test
    void capabilityCodeCasingCannotForkTheGroup() {
        createUser(OWNER);
        CapabilityOwnership.grant(identity, OWNER, TENANT, "forms");

        assertThat(CapabilityOwnership.isOwner(identity, OWNER, TENANT, "FORMS")).isTrue();
        assertThat(CapabilityOwnership.ownerGroupId(TENANT, "forms"))
                .isEqualTo(CapabilityOwnership.ownerGroupId(TENANT, "FORMS"));
    }

    @Test
    void approverGroupFallsBackToTenantOwnersWhenNobodyOwnsTheCapability() {
        assertThat(CapabilityOwnership.approverGroupId(identity, TENANT, CODE))
                .isEqualTo(TenantOwnership.groupId(TENANT));
    }

    @Test
    void approverGroupIsTheResourceOwnersOnceThereAreSome() {
        createUser(OWNER);
        CapabilityOwnership.grant(identity, OWNER, TENANT, CODE);

        assertThat(CapabilityOwnership.approverGroupId(identity, TENANT, CODE))
                .isEqualTo(CapabilityOwnership.ownerGroupId(TENANT, CODE));
        // ...and only for that capability.
        assertThat(CapabilityOwnership.approverGroupId(identity, TENANT, OTHER_CODE))
                .isEqualTo(TenantOwnership.groupId(TENANT));
    }

    /** An owners group that exists but has been emptied must not capture tasks. */
    @Test
    void anEmptiedOwnersGroupFallsBackAgain() {
        createUser(OWNER);
        CapabilityOwnership.grant(identity, OWNER, TENANT, CODE);
        CapabilityOwnership.revoke(identity, OWNER, TENANT, CODE);

        assertThat(CapabilityOwnership.approverGroupId(identity, TENANT, CODE))
                .isEqualTo(TenantOwnership.groupId(TENANT));
    }
}
