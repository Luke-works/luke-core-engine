package com.luke.engine.config;

import java.util.ArrayList;
import java.util.List;
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
 * Seeds the platform's RBAC role groups and their authorizations ("authorization
 * spaces") on first boot. Idempotent: only creates groups/authorizations that
 * are missing, and reconciles group type/name.
 *
 * <p><b>RBAC vs ABAC.</b> Role groups created here are tagged {@code type=ROLE}
 * and carry authorizations (what a user may do). Organizational / candidate
 * groups ({@code type=ORGANIZATIONAL}) are created by admins and carry no
 * authorizations — they're attributes used for task routing
 * ({@code candidateGroups}). The {@code type} field is the differentiator.
 *
 * <p><b>Access tiers.</b> Every role is seeded in two tiers, both {@code ROLE}:
 * <ul>
 *   <li>{@code <role>} — Read &amp; Write (read perms + write perms)</li>
 *   <li>{@code <role>-readonly} — Read Only (read perms only)</li>
 * </ul>
 * Onboarding picks the role and the access tier.
 *
 * <p>Tenant <em>isolation</em> is separate: grants use resourceId {@code *}, and
 * the tenant a user belongs to limits what they actually see/touch.
 */
@Component
public class RoleAuthorizationInitializer {

    private static final Logger log = LoggerFactory.getLogger(RoleAuthorizationInitializer.class);

    static final String ROLE_TYPE = "ROLE";
    static final String READONLY_SUFFIX = "-readonly";

    private final IdentityService identityService;
    private final AuthorizationService authorizationService;

    public RoleAuthorizationInitializer(IdentityService identityService, AuthorizationService authorizationService) {
        this.identityService = identityService;
        this.authorizationService = authorizationService;
    }

    /** read = permissions kept in the Read-Only tier; write = added in the Read & Write tier. */
    private record Grant(Resource resource, List<Permission> read, List<Permission> write) {}

    private record RoleDef(String id, String name, List<Grant> grants) {}

    @EventListener(ApplicationReadyEvent.class)
    public void ensureRoles() {
        for (RoleDef role : roleDefinitions()) {
            // Read & Write tier
            ensureGroup(role.id(), role.name(), ROLE_TYPE);
            for (Grant g : role.grants()) {
                grant(role.id(), g.resource(), concat(g.read(), g.write()));
            }
            // Read Only tier
            String roId = role.id() + READONLY_SUFFIX;
            ensureGroup(roId, role.name() + " (Read Only)", ROLE_TYPE);
            for (Grant g : role.grants()) {
                if (!g.read().isEmpty()) {
                    grant(roId, g.resource(), g.read().toArray(Permission[]::new));
                }
            }
        }

        // tenant-viewer is superseded by the Read-Only tier of any role — remove it.
        removeRole("tenant-viewer");

        log.info("RBAC role authorization spaces ensured (read-write + read-only tiers).");
    }

    private List<RoleDef> roleDefinitions() {
        List<RoleDef> roles = new ArrayList<>();

        // tenant-admin — full control of the tenant's runtime data
        roles.add(new RoleDef(RoleAuthorizationInitializer.TENANT_ADMIN, "Tenant Admin", List.of(
                new Grant(Resources.PROCESS_DEFINITION, reads(Permissions.READ, Permissions.READ_INSTANCE, Permissions.READ_HISTORY), writes(Permissions.ALL)),
                new Grant(Resources.PROCESS_INSTANCE, reads(Permissions.READ), writes(Permissions.ALL)),
                new Grant(Resources.TASK, reads(Permissions.READ), writes(Permissions.ALL)),
                new Grant(Resources.DEPLOYMENT, reads(Permissions.READ), writes(Permissions.ALL)),
                new Grant(Resources.DECISION_DEFINITION, reads(Permissions.READ, Permissions.READ_HISTORY), writes(Permissions.ALL)),
                new Grant(Resources.DECISION_REQUIREMENTS_DEFINITION, reads(Permissions.READ), writes(Permissions.ALL)),
                new Grant(Resources.BATCH, reads(Permissions.READ), writes(Permissions.ALL)))));

        // tenant-user — general operational user
        roles.add(new RoleDef(TENANT_USER, "Tenant User", List.of(
                new Grant(Resources.PROCESS_DEFINITION, reads(Permissions.READ, Permissions.READ_INSTANCE, Permissions.READ_HISTORY), writes(Permissions.CREATE_INSTANCE)),
                new Grant(Resources.PROCESS_INSTANCE, reads(Permissions.READ), writes(Permissions.CREATE, Permissions.UPDATE)),
                new Grant(Resources.TASK, reads(Permissions.READ), writes(Permissions.UPDATE, Permissions.TASK_WORK, Permissions.TASK_ASSIGN)),
                new Grant(Resources.DEPLOYMENT, reads(Permissions.READ), writes()),
                new Grant(Resources.DECISION_DEFINITION, reads(Permissions.READ, Permissions.READ_HISTORY), writes()),
                new Grant(Resources.DECISION_REQUIREMENTS_DEFINITION, reads(Permissions.READ), writes()))));

        // task-worker — human-task operator (may also start instances)
        roles.add(new RoleDef(TASK_WORKER, "Task Worker", List.of(
                new Grant(Resources.PROCESS_DEFINITION, reads(Permissions.READ, Permissions.READ_INSTANCE), writes(Permissions.CREATE_INSTANCE)),
                new Grant(Resources.PROCESS_INSTANCE, reads(Permissions.READ), writes(Permissions.CREATE)),
                new Grant(Resources.TASK, reads(Permissions.READ), writes(Permissions.UPDATE, Permissions.TASK_WORK, Permissions.TASK_ASSIGN)))));

        // process-operator — monitoring / ops (no deploy)
        roles.add(new RoleDef(PROCESS_OPERATOR, "Process Operator", List.of(
                new Grant(Resources.PROCESS_DEFINITION, reads(Permissions.READ, Permissions.READ_INSTANCE, Permissions.READ_HISTORY), writes()),
                new Grant(Resources.PROCESS_INSTANCE, reads(Permissions.READ), writes(Permissions.UPDATE, Permissions.DELETE)),
                new Grant(Resources.TASK, reads(Permissions.READ), writes()),
                new Grant(Resources.BATCH, reads(Permissions.READ), writes(Permissions.CREATE)))));

        // deployer — developer / Modeler persona
        roles.add(new RoleDef(DEPLOYER, "Deployer", List.of(
                new Grant(Resources.DEPLOYMENT, reads(Permissions.READ), writes(Permissions.CREATE, Permissions.DELETE)),
                new Grant(Resources.PROCESS_DEFINITION, reads(Permissions.READ, Permissions.READ_INSTANCE, Permissions.READ_HISTORY), writes(Permissions.CREATE_INSTANCE)),
                new Grant(Resources.PROCESS_INSTANCE, reads(Permissions.READ), writes(Permissions.CREATE)),
                new Grant(Resources.DECISION_DEFINITION, reads(Permissions.READ), writes()),
                new Grant(Resources.DECISION_REQUIREMENTS_DEFINITION, reads(Permissions.READ), writes()))));

        return roles;
    }

    static final String TENANT_ADMIN = "tenant-admin";
    static final String TENANT_USER = "tenant-user";
    static final String TASK_WORKER = "task-worker";
    static final String PROCESS_OPERATOR = "process-operator";
    static final String DEPLOYER = "deployer";

    private static List<Permission> reads(Permission... perms) {
        return List.of(perms);
    }

    private static List<Permission> writes(Permission... perms) {
        return List.of(perms);
    }

    private static Permission[] concat(List<Permission> a, List<Permission> b) {
        List<Permission> all = new ArrayList<>(a);
        all.addAll(b);
        return all.toArray(Permission[]::new);
    }

    private void ensureGroup(String id, String name, String type) {
        Group existing = identityService.createGroupQuery().groupId(id).singleResult();
        if (existing == null) {
            log.info("Creating role group: {} ({})", id, name);
            Group group = identityService.newGroup(id);
            group.setName(name);
            group.setType(type);
            identityService.saveGroup(group);
            return;
        }
        // Reconcile type/name on previously-seeded groups.
        if (!type.equals(existing.getType()) || !name.equals(existing.getName())) {
            existing.setName(name);
            existing.setType(type);
            identityService.saveGroup(existing);
        }
    }

    /** Idempotently grant a group a set of permissions on all resources of a type. */
    private void grant(String groupId, Resource resource, Permission... permissions) {
        if (permissions.length == 0) {
            return;
        }
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
    }

    /** Remove a role group and all of its authorizations (used to retire superseded roles). */
    private void removeRole(String groupId) {
        if (identityService.createGroupQuery().groupId(groupId).count() == 0) {
            return;
        }
        authorizationService.createAuthorizationQuery().groupIdIn(groupId).list()
                .forEach(a -> authorizationService.deleteAuthorization(a.getId()));
        identityService.deleteGroup(groupId);
        log.info("Removed superseded role group: {}", groupId);
    }
}
