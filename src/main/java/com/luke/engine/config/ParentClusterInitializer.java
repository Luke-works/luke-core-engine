package com.luke.engine.config;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Creates the platform's "parent cluster" tenant on first boot and assigns the
 * bootstrap admin user to it.
 *
 * This is the operations tenant used by cluster managers via core-ui-tailadmin
 * to view cross-tenant metrics, deployments, incidents, etc. It is NOT a default
 * tenant for end-user signups — customer organizations create their own tenants
 * through consumer-ui's signup flow (POST /engine-rest/tenant/create).
 *
 * Idempotent: checks for the specific parent-cluster tenant ID rather than
 * "any tenant exists" so customer tenant creation doesn't suppress this.
 */
@Component
public class ParentClusterInitializer {

    private static final Logger log = LoggerFactory.getLogger(ParentClusterInitializer.class);

    private final IdentityService identityService;

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    @Value("${luke.tenant.parent-cluster-name:Parent Cluster}")
    private String parentClusterName;

    @Value("${camunda.bpm.admin-user.id:admin}")
    private String adminUserId;

    public ParentClusterInitializer(IdentityService identityService) {
        this.identityService = identityService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureParentClusterTenant() {
        Tenant existing = identityService.createTenantQuery()
                .tenantId(parentClusterId)
                .singleResult();

        if (existing != null) {
            log.debug("Parent cluster tenant '{}' already exists", parentClusterId);
            return;
        }

        log.info("Creating parent cluster tenant: {} ({})", parentClusterId, parentClusterName);
        Tenant tenant = identityService.newTenant(parentClusterId);
        tenant.setName(parentClusterName);
        identityService.saveTenant(tenant);

        try {
            identityService.createTenantUserMembership(parentClusterId, adminUserId);
            log.info("Assigned admin user '{}' to parent cluster tenant '{}'", adminUserId, parentClusterId);
        } catch (Exception e) {
            log.warn("Could not assign admin to parent cluster tenant: {}", e.getMessage());
        }
    }
}
