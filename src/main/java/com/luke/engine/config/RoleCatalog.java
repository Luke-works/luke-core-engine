package com.luke.engine.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for the platform role catalog (#43). Before this, the role set,
 * assignability and role→dimension mappings were re-declared by hand in three places
 * ({@code RoleAuthorizationInitializer}, {@code OrgAdminController}, {@code PermissionsController}) and
 * had already drifted — {@code deployer} was seeded and authorized but unreachable from the org-admin
 * API. Those consumers now derive everything from this enum, so they cannot drift, and a test pins the
 * seeded roles to it.
 *
 * <p>Each role carries two dimension mappings because the product surfaces roles through two distinct
 * projections (kept intentionally separate):
 * <ul>
 *   <li>{@link #dimension()} — the <b>management</b> view (the org-admin members table), one distinct
 *       dimension per assignable role: {@code tenantAdmin / tenantUser / processUser / taskUser}.</li>
 *   <li>{@link #accessDimension()} — the <b>effective-access</b> view ({@code /api/me/permissions}),
 *       a coarser rollup where {@code tenant-admin} folds into {@code tenantUser} (its admin status is
 *       surfaced separately as a boolean) and there is no distinct admin dimension.</li>
 * </ul>
 */
public enum RoleCatalog {

    TENANT_ADMIN("tenant-admin", "Tenant Admin", true, "tenantAdmin", "tenantUser"),
    TENANT_USER("tenant-user", "Tenant User", true, "tenantUser", "tenantUser"),
    PROCESS_OPERATOR("process-operator", "Process Operator", true, "processUser", "processUser"),
    TASK_WORKER("task-worker", "Task Worker", true, "taskUser", "taskUser"),

    /**
     * INTERNAL-ONLY (#43): {@code deployer} is seeded and authorized (the developer / Modeler persona
     * with deploy rights), but is deliberately NOT tenant-assignable through the org-admin API —
     * granting deploy rights is a platform concern, not a self-serve org role. Making {@code assignable}
     * explicit here resolves the former "seeded but unreachable" inconsistency: it's intentional, not an
     * accident. (If the product later wants org owners to grant it, flip this to {@code true} and add a
     * management dimension slot.)
     */
    DEPLOYER("deployer", "Deployer", false, "processUser", "processUser");

    private final String id;
    private final String displayName;
    private final boolean assignable;
    private final String dimension;
    private final String accessDimension;

    RoleCatalog(String id, String displayName, boolean assignable, String dimension, String accessDimension) {
        this.id = id;
        this.displayName = displayName;
        this.assignable = assignable;
        this.dimension = dimension;
        this.accessDimension = accessDimension;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public boolean assignable() { return assignable; }
    public String dimension() { return dimension; }
    public String accessDimension() { return accessDimension; }

    /** Ids an org owner may assign (org-admin API) — the assignable roles. */
    public static Set<String> assignableIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (RoleCatalog r : values()) {
            if (r.assignable) {
                ids.add(r.id);
            }
        }
        return ids;
    }

    /** Every role id in the catalog (including internal-only). */
    public static Set<String> allIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (RoleCatalog r : values()) {
            ids.add(r.id);
        }
        return ids;
    }

    /** Assignable role id → MANAGEMENT dimension (org-admin members view). */
    public static Map<String, String> assignableDimensions() {
        Map<String, String> m = new LinkedHashMap<>();
        for (RoleCatalog r : values()) {
            if (r.assignable) {
                m.put(r.id, r.dimension);
            }
        }
        return m;
    }

    /** Every role id → EFFECTIVE-ACCESS dimension (/api/me/permissions rollup). */
    public static Map<String, String> accessDimensions() {
        Map<String, String> m = new LinkedHashMap<>();
        for (RoleCatalog r : values()) {
            m.put(r.id, r.accessDimension);
        }
        return m;
    }
}
