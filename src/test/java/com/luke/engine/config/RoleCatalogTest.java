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
}
