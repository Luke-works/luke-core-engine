package com.luke.engine.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * #43: pins the single role catalog to its consumers so they can't drift again. The org-admin
 * (assignable/management) and permissions (effective-access) views now DERIVE from {@link RoleCatalog}
 * at compile time; this guards the one remaining hand-maintained link — the roles
 * {@code RoleAuthorizationInitializer} actually seeds — plus the deployer decision.
 */
class RoleCatalogTest {

    @Test
    void everySeededRoleIsInTheCatalogAndEveryCatalogRoleIsSeeded() {
        assertThat(RoleAuthorizationInitializer.seededRoleIds())
                .as("seeded roles must match the catalog exactly (no seeded-but-uncatalogued or catalogued-but-unseeded role)")
                .containsExactlyInAnyOrderElementsOf(RoleCatalog.allIds());
    }

    @Test
    void deployerIsInternalOnlyAndEverythingElseIsAssignable() {
        // The documented resolution of the old "seeded but unreachable" inconsistency.
        assertThat(RoleCatalog.DEPLOYER.assignable()).isFalse();
        assertThat(RoleCatalog.assignableIds())
                .containsExactlyInAnyOrder("tenant-admin", "tenant-user", "process-operator", "task-worker")
                .doesNotContain("deployer");
    }

    @Test
    void everyRoleIsFullyDefined() {
        for (RoleCatalog r : RoleCatalog.values()) {
            assertThat(r.id()).isNotBlank();
            assertThat(r.displayName()).isNotBlank();
            assertThat(r.dimension()).isNotBlank();
            assertThat(r.accessDimension()).isNotBlank();
        }
    }

    @Test
    void dimensionMappingsCoverExactlyTheirRoleSetsAndKnownProjections() {
        assertThat(RoleCatalog.assignableDimensions().keySet()).isEqualTo(RoleCatalog.assignableIds());
        assertThat(RoleCatalog.accessDimensions().keySet()).isEqualTo(RoleCatalog.allIds());

        Set<String> managementDims = Set.of("tenantAdmin", "tenantUser", "processUser", "taskUser");
        Set<String> accessDims = Set.of("tenantUser", "processUser", "taskUser");
        assertThat(RoleCatalog.assignableDimensions().values()).allMatch(managementDims::contains);
        assertThat(RoleCatalog.accessDimensions().values()).allMatch(accessDims::contains);
    }

    // ── #61: WorkOS role slug → catalog mapping (the IdP→engine SSOT) ──────────────────────

    @Test
    void fromWorkosSlug_mapsTheFourMirrorSlugsByIdentity() {
        // The WorkOS control plane mirrors these slugs 1:1, so they map to themselves.
        assertThat(RoleCatalog.fromWorkosSlug("tenant-admin")).contains(RoleCatalog.TENANT_ADMIN);
        assertThat(RoleCatalog.fromWorkosSlug("tenant-user")).contains(RoleCatalog.TENANT_USER);
        assertThat(RoleCatalog.fromWorkosSlug("process-operator")).contains(RoleCatalog.PROCESS_OPERATOR);
        assertThat(RoleCatalog.fromWorkosSlug("task-worker")).contains(RoleCatalog.TASK_WORKER);
    }

    @Test
    void fromWorkosSlug_aliasesWorkosBuiltInsToTheNearestCatalogRole() {
        // WorkOS's undeletable built-ins have no 1:1 engine equivalent → nearest role.
        assertThat(RoleCatalog.fromWorkosSlug("member")).contains(RoleCatalog.TENANT_USER);
        assertThat(RoleCatalog.fromWorkosSlug("admin")).contains(RoleCatalog.TENANT_ADMIN);
    }

    @Test
    void fromWorkosSlug_isCaseInsensitiveAndTrims() {
        assertThat(RoleCatalog.fromWorkosSlug("  Tenant-Admin ")).contains(RoleCatalog.TENANT_ADMIN);
        assertThat(RoleCatalog.fromWorkosSlug("MEMBER")).contains(RoleCatalog.TENANT_USER);
    }

    @Test
    void fromWorkosSlug_deniesUnknownBlankNullAndInternalOnlyDeployer() {
        // Unknown IdP role grants nothing (fail-closed).
        assertThat(RoleCatalog.fromWorkosSlug("superadmin")).isEmpty();
        assertThat(RoleCatalog.fromWorkosSlug("")).isEmpty();
        assertThat(RoleCatalog.fromWorkosSlug("   ")).isEmpty();
        assertThat(RoleCatalog.fromWorkosSlug(null)).isEmpty();
        // deployer is internal-only and NOT in the WorkOS mirror set → never reachable from a claim.
        assertThat(RoleCatalog.fromWorkosSlug("deployer")).isEmpty();
    }
}
