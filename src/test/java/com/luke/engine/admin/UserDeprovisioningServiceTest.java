package com.luke.engine.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * #38 (core-engine side): operator/IdP deprovisioning must revoke ALL of a user's engine access —
 * tenant + group memberships and the user itself — and delete a tenant they solely occupied, so a
 * removed IdP user doesn't outlive their identity. Idempotent for an unknown user.
 */
@SpringBootTest
class UserDeprovisioningServiceTest {

    @Autowired
    private IdentityService identity;

    @Autowired
    private UserDeprovisioningService deprovisioning;

    private static final String USER = "deprov-test-user";
    private static final String TENANT = "deprov-test-tenant";
    private static final String GROUP = "tenant-user"; // a real global role group created at startup

    @AfterEach
    void cleanup() {
        try { identity.deleteMembership(USER, GROUP); } catch (RuntimeException ignore) { }
        try { identity.deleteTenantUserMembership(TENANT, USER); } catch (RuntimeException ignore) { }
        try { identity.deleteUser(USER); } catch (RuntimeException ignore) { }
        try { identity.deleteTenant(TENANT); } catch (RuntimeException ignore) { }
    }

    @Test
    void deprovisionRemovesMembershipsUserAndSoleOwnedTenant() {
        var u = identity.newUser(USER);
        u.setPassword("x");
        identity.saveUser(u);
        var t = identity.newTenant(TENANT);
        identity.saveTenant(t);
        identity.createTenantUserMembership(TENANT, USER);
        identity.createMembership(USER, GROUP);
        assertThat(identity.createUserQuery().userId(USER).count()).isEqualTo(1);

        List<String> deletedTenants = deprovisioning.deprovision(USER);

        assertThat(identity.createUserQuery().userId(USER).count()).as("user deleted").isZero();
        assertThat(identity.createGroupQuery().groupId(GROUP).groupMember(USER).count())
                .as("group membership removed").isZero();
        assertThat(deletedTenants).as("sole-owned tenant deleted").contains(TENANT);
        assertThat(identity.createTenantQuery().tenantId(TENANT).count())
                .as("tenant deleted").isZero();
    }

    @Test
    void deprovisionUnknownUserIsANoOp() {
        assertThatCode(() -> {
            List<String> deleted = deprovisioning.deprovision("nobody-deprov-xyz");
            assertThat(deleted).isEmpty();
        }).doesNotThrowAnyException();
    }
}
