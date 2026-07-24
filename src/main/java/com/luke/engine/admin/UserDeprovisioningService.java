package com.luke.engine.admin;

import com.luke.engine.capability.access.CapabilityAdminController;
import java.util.ArrayList;
import java.util.List;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.finos.fluxnova.bpm.engine.identity.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Removes all of a user's access from the engine — the shared cleanup behind both
 * self-service account deletion ({@link AccountController}) and operator/IdP-driven
 * deprovisioning ({@code OnboardingController#deprovisionUser}, for SCIM / directory-sync
 * leaver events, auth-engine #38).
 *
 * <p>Drops the user's tenant + group memberships, deletes the engine user, purges their
 * capability grants everywhere, and deletes any tenant they solely occupied (which would
 * otherwise be orphaned). Every step is best-effort ({@link #safe}) so one failure can't
 * strand the rest, and the whole thing is idempotent — deprovisioning an unknown user is a
 * successful no-op.
 */
@Service
public class UserDeprovisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserDeprovisioningService.class);

    private final IdentityService identityService;
    private final CapabilityAdminController capabilityAdmin;

    public UserDeprovisioningService(IdentityService identityService, CapabilityAdminController capabilityAdmin) {
        this.identityService = identityService;
        this.capabilityAdmin = capabilityAdmin;
    }

    /**
     * Revoke all access for {@code userId}.
     *
     * @return the ids of tenants deleted because this user was their sole member
     */
    public List<String> deprovision(String userId) {
        // Nothing provisioned in the engine → only make sure no capability grants linger.
        if (identityService.createUserQuery().userId(userId).count() == 0) {
            safe(() -> capabilityAdmin.purgeUser(userId));
            return List.of();
        }

        // 1. Tenants the user belongs to, and which of them they solely occupy.
        List<String> tenants = identityService.createTenantQuery().userMember(userId).list()
                .stream().map(Tenant::getId).toList();
        List<String> soleOwned = new ArrayList<>();
        for (String t : tenants) {
            if (identityService.createUserQuery().memberOfTenant(t).count() == 1) {
                soleOwned.add(t);
            }
        }

        // 2. Drop the user's memberships (tenant + group), then the user itself.
        for (String t : tenants) {
            safe(() -> identityService.deleteTenantUserMembership(t, userId));
        }
        for (Group g : identityService.createGroupQuery().groupMember(userId).list()) {
            safe(() -> identityService.deleteMembership(userId, g.getId()));
        }
        safe(() -> identityService.deleteUser(userId));

        // 3. Capability cleanup: the user's grants everywhere, and every sole-owned tenant.
        safe(() -> capabilityAdmin.purgeUser(userId));
        for (String t : soleOwned) {
            safe(() -> identityService.deleteTenant(t));
            safe(() -> capabilityAdmin.purgeTenant(t));
        }

        log.info("Deprovisioned user '{}' ({} memberships removed, {} tenants deleted)",
                userId, tenants.size(), soleOwned.size());
        return soleOwned;
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("Deprovision cleanup step failed (continuing): {}", e.getMessage());
        }
    }
}
