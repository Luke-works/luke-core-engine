package com.luke.engine.backfill;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Tenant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Backfills the platform admin into tenants created before auto-provisioning existed,
 * so support access is consistent across all tenants. New tenants get the admin at
 * creation time (see {@code OrganizationController}); this catches the rest.
 *
 * <p>Idempotent: only adds the membership where it's missing, and skips the parent
 * cluster (the admin is already a member there). Membership only — no org role.
 */
@Component
public class AdminTenantMembershipBackfill implements Backfill {

    private final IdentityService identityService;

    @Value("${camunda.bpm.admin-user.id:admin}")
    private String adminUserId;

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    public AdminTenantMembershipBackfill(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public String name() {
        return "admin-tenant-membership";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public int run() {
        if (adminUserId == null || adminUserId.isBlank()) {
            return 0;
        }
        // The admin user is created during Camunda bootstrap; if it isn't present yet,
        // skip — the next boot will apply it.
        if (identityService.createUserQuery().userId(adminUserId).count() == 0) {
            return 0;
        }

        int added = 0;
        for (Tenant tenant : identityService.createTenantQuery().list()) {
            String tenantId = tenant.getId();
            if (parentClusterId.equals(tenantId)) {
                continue; // admin is already a member of the parent cluster
            }
            boolean isMember = identityService.createTenantQuery()
                    .tenantId(tenantId).userMember(adminUserId).count() > 0;
            if (!isMember) {
                identityService.createTenantUserMembership(tenantId, adminUserId);
                added++;
            }
        }
        return added;
    }
}
