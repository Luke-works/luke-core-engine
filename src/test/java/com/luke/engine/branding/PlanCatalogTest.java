package com.luke.engine.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.capability.Capability;
import com.luke.engine.capability.capability.CapabilityRepository;
import com.luke.engine.capability.capability.CapabilitySeed;
import com.luke.engine.config.BootCoordinator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/** Pins the plan-tier SSOT the way {@code RoleCatalogTest} pins roles: every tier is fully defined,
 *  capability inclusion is monotonic across tiers, the fail-closed resolution holds, and every seeded
 *  tenant-facing capability is sellable on some plan (Enterprise). */
class PlanCatalogTest {

    @Test
    void everyTierIsFullyDefinedAndRanksAreDistinctAndOrdered() {
        int prev = -1;
        for (PlanCatalog t : PlanCatalog.values()) {
            assertThat(t.id()).as("id").isNotBlank();
            assertThat(t.displayName()).as("displayName").isNotBlank();
            assertThat(t.includedCapabilities()).as(t.id() + " capabilities").isNotEmpty();
            assertThat(t.rank()).as(t.id() + " rank ascending").isGreaterThan(prev);
            prev = t.rank();
        }
    }

    @Test
    void capabilityInclusionIsMonotonic() {
        // A higher tier includes everything a lower tier does — you never lose a module by paying more.
        PlanCatalog[] tiers = PlanCatalog.values();
        for (int i = 1; i < tiers.length; i++) {
            for (String code : tiers[i - 1].includedCapabilities()) {
                assertThat(tiers[i].includes(code))
                        .as(tiers[i].id() + " must include " + code + " (in " + tiers[i - 1].id() + ")")
                        .isTrue();
            }
        }
    }

    @Test
    void entitlementsMatchThePricingModel() {
        assertThat(PlanCatalog.FREE.isPaid()).isFalse();
        assertThat(PlanCatalog.PRO.isPaid()).isTrue();

        // Branding: locked on for FREE, removable on every paying tier.
        assertThat(PlanCatalog.FREE.removableBranding()).isFalse();
        assertThat(PlanCatalog.PRO.removableBranding()).isTrue();

        // SSO: Business and up. Self-host + voice: Enterprise only.
        assertThat(PlanCatalog.PRO.sso()).isFalse();
        assertThat(PlanCatalog.BUSINESS.sso()).isTrue();
        assertThat(PlanCatalog.BUSINESS.selfHost()).isFalse();
        assertThat(PlanCatalog.ENTERPRISE.selfHost()).isTrue();
        assertThat(PlanCatalog.ENTERPRISE.voice()).isTrue();

        // Enterprise is custom/unlimited (sentinel -1).
        assertThat(PlanCatalog.ENTERPRISE.priceUsd()).isEqualTo(-1);
        assertThat(PlanCatalog.ENTERPRISE.monthlySubmissions()).isEqualTo(-1);

        // Free = Forms only; Pro adds Email.
        assertThat(PlanCatalog.FREE.includes("FORMS")).isTrue();
        assertThat(PlanCatalog.FREE.includes("EMAIL")).isFalse();
        assertThat(PlanCatalog.PRO.includes("EMAIL")).isTrue();
    }

    @Test
    void fromStoredIsFailClosedWithLegacyPaidAlias() {
        assertThat(PlanCatalog.fromStored(null)).isEqualTo(PlanCatalog.FREE);
        assertThat(PlanCatalog.fromStored("")).isEqualTo(PlanCatalog.FREE);
        assertThat(PlanCatalog.fromStored("  ")).isEqualTo(PlanCatalog.FREE);
        assertThat(PlanCatalog.fromStored("GOLD")).isEqualTo(PlanCatalog.FREE);         // unknown → free
        assertThat(PlanCatalog.fromStored("PAID")).isEqualTo(PlanCatalog.PRO);          // legacy alias
        assertThat(PlanCatalog.fromStored("  business ")).isEqualTo(PlanCatalog.BUSINESS); // trims + case
        for (PlanCatalog t : PlanCatalog.values()) {
            assertThat(PlanCatalog.fromStored(t.id())).isEqualTo(t);
        }
    }

    @Test
    void everySeededTenantCapabilityIsSellableOnEnterprise() {
        // Drift guard: if a new tenant-facing capability is added to CapabilitySeed, the top tier must
        // include it — otherwise it could never be bought. Run the real seeder against a mock repo.
        CapabilityRepository repo = mock(CapabilityRepository.class);
        BootCoordinator boot = mock(BootCoordinator.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(boot).runExclusive(anyString(), any(Runnable.class));
        when(repo.existsByCode(anyString())).thenReturn(false);
        List<String> seeded = new ArrayList<>();
        when(repo.save(any(Capability.class))).thenAnswer(inv -> {
            seeded.add(((Capability) inv.getArgument(0)).getCode());
            return inv.getArgument(0);
        });

        new CapabilitySeed(repo, boot).run(mock(ApplicationArguments.class));

        assertThat(seeded).isNotEmpty();
        for (String code : seeded) {
            assertThat(PlanCatalog.ENTERPRISE.includes(code))
                    .as("seeded capability " + code + " must be sellable on ENTERPRISE")
                    .isTrue();
        }
    }
}
