package com.luke.engine.tenant;

import java.util.ArrayList;
import java.util.List;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.Tenant;
import org.cibseven.bpm.engine.identity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One-time, idempotent migration that seeds the per-tenant {@link TenantOwnership} binding for
 * tenants created before scoped ownership existed. Runs once at startup and no-ops thereafter.
 *
 * <p><b>The hard part.</b> The pre-scoped model never recorded who <em>created</em> (owns) a
 * tenant — only that a user is a member and holds the <em>global</em> {@code tenant-admin} role.
 * So for a user who is a global admin AND a member of several orgs we cannot tell which they own
 * from which they were merely invited into. This backfill therefore seeds an owner <b>only when
 * it is unambiguous</b>: a global-admin member who belongs to exactly one (non-parent) org is,
 * by construction, that org's creator. Anything ambiguous is <b>logged for an operator</b> and
 * never auto-granted — we under-grant-and-recover rather than silently re-create the escalation
 * (a platform operator bypasses the scoped check and can assign the correct owner at any time).
 */
@Component
public class TenantOwnerBackfill {

    private static final Logger log = LoggerFactory.getLogger(TenantOwnerBackfill.class);
    private static final String CAMUNDA_ADMIN_GROUP = "camunda-admin";
    private static final String TENANT_ADMIN = "tenant-admin";
    private static final String TENANT_ADMIN_READONLY = "tenant-admin-readonly";

    private final IdentityService identity;

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    @Value("${camunda.bpm.admin-user.id:admin}")
    private String adminUserId;

    public TenantOwnerBackfill(IdentityService identity) {
        this.identity = identity;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        try {
            run();
        } catch (RuntimeException e) {
            // Never block startup on a best-effort migration.
            log.warn("Tenant-owner backfill skipped ({})", e.getMessage());
        }
    }

    private void run() {
        int seeded = 0;
        int tenantsSeeded = 0;
        List<String> needsManual = new ArrayList<>();

        for (Tenant t : identity.createTenantQuery().list()) {
            String tenantId = t.getId();
            if (tenantId.equals(parentClusterId)) continue;
            if (TenantOwnership.ownerCount(identity, tenantId) > 0) continue; // already migrated

            List<String> ambiguous = new ArrayList<>();
            boolean seededThis = false;
            for (User u : identity.createUserQuery().memberOfTenant(tenantId).list()) {
                String uid = u.getId();
                if (uid.equals(adminUserId)) continue; // platform support account, not an owner

                List<Group> groups = identity.createGroupQuery().groupMember(uid).list();
                if (groups.stream().anyMatch(g -> CAMUNDA_ADMIN_GROUP.equals(g.getId()))) continue; // platform operator
                boolean globalAdmin = groups.stream()
                        .anyMatch(g -> TENANT_ADMIN.equals(g.getId()) || TENANT_ADMIN_READONLY.equals(g.getId()));
                if (!globalAdmin) continue;

                long orgs = identity.createTenantQuery().userMember(uid).list().stream()
                        .filter(x -> !x.getId().equals(parentClusterId)).count();
                if (orgs == 1) {
                    TenantOwnership.grant(identity, uid, tenantId); // unambiguous: their only org → their org
                    seeded++;
                    seededThis = true;
                } else {
                    ambiguous.add(uid); // global-admin of several orgs — cannot infer which they own
                }
            }

            if (seededThis) {
                tenantsSeeded++;
            } else if (!ambiguous.isEmpty()) {
                needsManual.add(tenantId + " (ambiguous global-admins: " + String.join(", ", ambiguous) + ")");
            } else {
                needsManual.add(tenantId + " (no eligible admin member found)");
            }
        }

        if (seeded == 0 && needsManual.isEmpty()) return; // nothing to migrate
        log.info("Tenant-owner backfill: seeded {} owner(s) across {} tenant(s).{}",
                seeded, tenantsSeeded,
                needsManual.isEmpty()
                        ? ""
                        : " An operator must assign an owner (POST /api/org role tenant-admin) for: "
                                + String.join("; ", needsManual));
    }
}
