package com.luke.engine.config;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.Permission;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.identity.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Seeds the platform's role groups and their authorizations ("authorization
 * spaces") on first boot, so users can be onboarded into a role and inherit the
 * right grants. Idempotent: only creates groups/authorizations that are missing.
 *
 * <p>Tenant <em>isolation</em> is handled separately by tenant membership +
 * tenant checks. These authorizations are granted with resourceId {@code *};
 * the tenant a user belongs to then limits which data they actually see/touch.
 *
 * <ul>
 *   <li><b>tenant-user</b> — day-to-day operator: read + start instances + work
 *       tasks + read deployments/decisions/history.</li>
 *   <li><b>tenant-admin</b> — full control of their tenant's runtime data
 *       (process defs/instances, tasks, deployments, decisions, batches).
 *       Global identity management (users/groups/tenants) is intentionally
 *       reserved for operators (camunda-admin), since identity is not
 *       tenant-scoped in the engine.</li>
 * </ul>
 */
@Component
public class RoleAuthorizationInitializer {

    private static final Logger log = LoggerFactory.getLogger(RoleAuthorizationInitializer.class);

    static final String TENANT_ADMIN = "tenant-admin";
    static final String TENANT_USER = "tenant-user";

    private final IdentityService identityService;
    private final AuthorizationService authorizationService;

    public RoleAuthorizationInitializer(IdentityService identityService, AuthorizationService authorizationService) {
        this.identityService = identityService;
        this.authorizationService = authorizationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureRoles() {
        ensureGroup(TENANT_USER, "Tenant User");
        ensureGroup(TENANT_ADMIN, "Tenant Admin");

        // ── tenant-user ────────────────────────────────────────────────
        grant(TENANT_USER, Resources.PROCESS_DEFINITION,
                Permissions.READ, Permissions.READ_INSTANCE, Permissions.CREATE_INSTANCE, Permissions.READ_HISTORY);
        grant(TENANT_USER, Resources.PROCESS_INSTANCE,
                Permissions.READ, Permissions.CREATE, Permissions.UPDATE);
        grant(TENANT_USER, Resources.TASK,
                Permissions.READ, Permissions.UPDATE, Permissions.TASK_WORK, Permissions.TASK_ASSIGN);
        grant(TENANT_USER, Resources.DEPLOYMENT, Permissions.READ);
        grant(TENANT_USER, Resources.DECISION_DEFINITION, Permissions.READ, Permissions.READ_HISTORY);
        grant(TENANT_USER, Resources.DECISION_REQUIREMENTS_DEFINITION, Permissions.READ);

        // ── tenant-admin ───────────────────────────────────────────────
        grant(TENANT_ADMIN, Resources.PROCESS_DEFINITION, Permissions.ALL);
        grant(TENANT_ADMIN, Resources.PROCESS_INSTANCE, Permissions.ALL);
        grant(TENANT_ADMIN, Resources.TASK, Permissions.ALL);
        grant(TENANT_ADMIN, Resources.DEPLOYMENT, Permissions.ALL);
        grant(TENANT_ADMIN, Resources.DECISION_DEFINITION, Permissions.ALL);
        grant(TENANT_ADMIN, Resources.DECISION_REQUIREMENTS_DEFINITION, Permissions.ALL);
        grant(TENANT_ADMIN, Resources.BATCH, Permissions.ALL);

        log.info("Role authorization spaces ensured: {}, {}", TENANT_USER, TENANT_ADMIN);
    }

    private void ensureGroup(String id, String name) {
        if (identityService.createGroupQuery().groupId(id).count() > 0) {
            return;
        }
        log.info("Creating role group: {} ({})", id, name);
        Group group = identityService.newGroup(id);
        group.setName(name);
        group.setType("SYSTEM");
        identityService.saveGroup(group);
    }

    /** Idempotently grant a group a set of permissions on all resources of a type. */
    private void grant(String groupId, Resource resource, Permission... permissions) {
        boolean exists = authorizationService.createAuthorizationQuery()
                .groupIdIn(groupId)
                .resourceType(resource.resourceType())
                .resourceId("*")
                .count() > 0;
        if (exists) {
            return;
        }
        Authorization authorization = authorizationService.createNewAuthorization(Authorization.AUTH_TYPE_GRANT);
        authorization.setGroupId(groupId);
        authorization.setResource(resource);
        authorization.setResourceId("*");
        for (Permission permission : permissions) {
            authorization.addPermission(permission);
        }
        authorizationService.saveAuthorization(authorization);
        log.info("Granted {} {} on {} (*)", groupId, java.util.Arrays.toString(permissions), resource.resourceName());
    }
}
