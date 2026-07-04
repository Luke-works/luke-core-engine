package com.luke.engine.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * INTEGRATION test against the real (H2) {@link IdentityService} — like {@link TenantOwnershipTest},
 * deliberately not mocked, so an identity-query bug can't hide (the same CIBSeven trap that made the
 * tenant-ownership check inert). Asserts a non-manager is NOT a manager, that grant/revoke move
 * membership, and that management is scoped to its own candidate group.
 */
@SpringBootTest
class CandidateGroupOwnershipTest {

    @Autowired
    private IdentityService identity;

    private static final String CG = "TEN-CG-TEST:sales";
    private static final String OTHER_CG = "TEN-CG-TEST:ops";
    private static final String MANAGER = "cg-test-manager";
    private static final String PLAIN = "cg-test-plain";

    @AfterEach
    void cleanup() {
        for (String cg : new String[] {CG, OTHER_CG}) {
            for (String u : new String[] {MANAGER, PLAIN}) {
                try { identity.deleteMembership(u, CandidateGroupOwnership.managerGroupId(cg)); } catch (RuntimeException ignore) { }
            }
            try { identity.deleteGroup(CandidateGroupOwnership.managerGroupId(cg)); } catch (RuntimeException ignore) { }
        }
        for (String u : new String[] {MANAGER, PLAIN}) {
            try { identity.deleteUser(u); } catch (RuntimeException ignore) { }
        }
    }

    private void createUser(String id) {
        var u = identity.newUser(id);
        u.setPassword("x");
        identity.saveUser(u);
    }

    @Test
    void managerGroupIdIsScopedToTheCandidateGroup() {
        assertThat(CandidateGroupOwnership.managerGroupId(CG)).isEqualTo("cgowner:" + CG);
    }

    @Test
    void grantMakesOnlyTheGrantedUserAManager() {
        createUser(MANAGER);
        createUser(PLAIN);

        assertThat(CandidateGroupOwnership.isManager(identity, MANAGER, CG)).isFalse();

        CandidateGroupOwnership.grant(identity, MANAGER, CG);

        assertThat(CandidateGroupOwnership.isManager(identity, MANAGER, CG)).isTrue();
        assertThat(CandidateGroupOwnership.isManager(identity, PLAIN, CG)).isFalse();
        assertThat(CandidateGroupOwnership.managerIds(identity, CG)).containsExactly(MANAGER);
    }

    @Test
    void managementIsScopedToItsOwnCandidateGroup() {
        createUser(MANAGER);
        CandidateGroupOwnership.grant(identity, MANAGER, CG);
        // Manager of :sales is NOT a manager of :ops.
        assertThat(CandidateGroupOwnership.isManager(identity, MANAGER, OTHER_CG)).isFalse();
    }

    @Test
    void grantIsIdempotentAndRevokeRemoves() {
        createUser(MANAGER);
        CandidateGroupOwnership.grant(identity, MANAGER, CG);
        CandidateGroupOwnership.grant(identity, MANAGER, CG);
        assertThat(CandidateGroupOwnership.managerIds(identity, CG)).containsExactly(MANAGER);

        CandidateGroupOwnership.revoke(identity, MANAGER, CG);
        assertThat(CandidateGroupOwnership.isManager(identity, MANAGER, CG)).isFalse();
    }
}
