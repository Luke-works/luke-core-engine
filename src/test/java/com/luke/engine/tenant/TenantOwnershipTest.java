package com.luke.engine.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.finos.fluxnova.bpm.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * INTEGRATION test against the REAL CIBSeven {@link IdentityService} (H2-backed) — deliberately not
 * mocked.
 *
 * <p>The previous version of this test mocked the identity queries, so it happily passed while the
 * production code was completely broken: {@code UserQuery.userId(u).memberOfGroup(g).count()} ignores
 * the group filter in CIBSeven and returns 1 for any existing user, which made {@code isOwner()} true
 * for everyone and made {@code grant()}'s idempotency guard skip the membership for everyone. A live
 * pentest (Suite 1) caught it in qa; a mock never could. This test exercises the real query engine so
 * the regression can't come back: it asserts a non-owner is NOT an owner (the exact thing the bug got
 * wrong) and that grant/revoke actually move membership.
 */
@SpringBootTest
class TenantOwnershipTest {

    @Autowired
    private IdentityService identity;

    private static final String TENANT = "TEN-OWN-TEST";
    private static final String OTHER_TENANT = "TEN-OTH-TEST";
    private static final String OWNER = "ownership-test-owner";
    private static final String MEMBER = "ownership-test-member";

    @AfterEach
    void cleanup() {
        for (String u : new String[] {OWNER, MEMBER}) {
            try { identity.deleteMembership(u, TenantOwnership.groupId(TENANT)); } catch (RuntimeException ignore) { }
            try { identity.deleteUser(u); } catch (RuntimeException ignore) { }
        }
        for (String t : new String[] {TENANT, OTHER_TENANT}) {
            try { identity.deleteGroup(TenantOwnership.groupId(t)); } catch (RuntimeException ignore) { }
            try { identity.deleteTenant(t); } catch (RuntimeException ignore) { }
        }
    }

    private void createUser(String id) {
        var u = identity.newUser(id);
        u.setPassword("x");
        identity.saveUser(u);
    }

    @Test
    void groupIdIsScopedToTenant() {
        assertThat(TenantOwnership.groupId(TENANT)).isEqualTo("owner:" + TENANT);
    }

    @Test
    void grantMakesOnlyTheGrantedUserAnOwner() {
        createUser(OWNER);
        createUser(MEMBER);

        // Before any grant, nobody is an owner.
        assertThat(TenantOwnership.isOwner(identity, OWNER, TENANT)).isFalse();
        assertThat(TenantOwnership.ownerCount(identity, TENANT)).isZero();

        TenantOwnership.grant(identity, OWNER, TENANT);

        // The granted user IS an owner; a different existing user is NOT — this is the exact
        // assertion the broken userId+memberOfGroup query got wrong (it returned true for everyone).
        assertThat(TenantOwnership.isOwner(identity, OWNER, TENANT)).isTrue();
        assertThat(TenantOwnership.isOwner(identity, MEMBER, TENANT)).isFalse();
        assertThat(TenantOwnership.ownerCount(identity, TENANT)).isEqualTo(1);
    }

    @Test
    void ownershipIsScopedToItsOwnTenant() {
        createUser(OWNER);
        TenantOwnership.grant(identity, OWNER, TENANT);
        // Owner of TENANT is NOT an owner of a different tenant.
        assertThat(TenantOwnership.isOwner(identity, OWNER, OTHER_TENANT)).isFalse();
    }

    @Test
    void grantIsIdempotent() {
        createUser(OWNER);
        TenantOwnership.grant(identity, OWNER, TENANT);
        TenantOwnership.grant(identity, OWNER, TENANT);
        assertThat(TenantOwnership.ownerCount(identity, TENANT)).isEqualTo(1);
    }

    @Test
    void revokeRemovesOwnership() {
        createUser(OWNER);
        TenantOwnership.grant(identity, OWNER, TENANT);
        TenantOwnership.revoke(identity, OWNER, TENANT);
        assertThat(TenantOwnership.isOwner(identity, OWNER, TENANT)).isFalse();
        assertThat(TenantOwnership.ownerCount(identity, TENANT)).isZero();
    }
}
