package com.luke.engine.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Plan resolution is fail-closed: absent row, blank tenant, unknown value, and lookup failure all
 *  degrade to FREE; a stored tier resolves to itself; legacy PAID → PRO. */
class PlanServiceTest {

    private final TenantPlanRepository plans = mock(TenantPlanRepository.class);
    private final PlanService service = new PlanService(plans);

    @Test
    void absentRowIsFree() {
        when(plans.findById("t1")).thenReturn(Optional.empty());
        assertThat(service.tierOf("t1")).isEqualTo(PlanCatalog.FREE);
    }

    @Test
    void blankTenantIsFreeWithoutHittingTheRepo() {
        assertThat(service.tierOf(null)).isEqualTo(PlanCatalog.FREE);
        assertThat(service.tierOf("  ")).isEqualTo(PlanCatalog.FREE);
    }

    @Test
    void aStoredTierResolvesToItself() {
        when(plans.findById("t1")).thenReturn(Optional.of(new TenantPlan("t1", "BUSINESS")));
        assertThat(service.tierOf("t1")).isEqualTo(PlanCatalog.BUSINESS);
    }

    @Test
    void legacyPaidRowResolvesToPro() {
        when(plans.findById("t1")).thenReturn(Optional.of(new TenantPlan("t1", TenantPlan.PLAN_PAID)));
        assertThat(service.tierOf("t1")).isEqualTo(PlanCatalog.PRO);
    }

    @Test
    void unknownStoredValueFailsClosedToFree() {
        when(plans.findById("t1")).thenReturn(Optional.of(new TenantPlan("t1", "GOLD")));
        assertThat(service.tierOf("t1")).isEqualTo(PlanCatalog.FREE);
    }

    @Test
    void aLookupFailureDegradesToFree() {
        when(plans.findById("t1")).thenThrow(new RuntimeException("db down"));
        assertThat(service.tierOf("t1")).isEqualTo(PlanCatalog.FREE);
    }

    @Test
    void includesCapabilityFollowsTheTier() {
        when(plans.findById("biz")).thenReturn(Optional.of(new TenantPlan("biz", "BUSINESS")));
        when(plans.findById("free")).thenReturn(Optional.empty());
        assertThat(service.includesCapability("biz", "SIGNATURES")).isTrue();
        assertThat(service.includesCapability("free", "EMAIL")).isFalse();
        assertThat(service.includesCapability("free", "FORMS")).isTrue();
    }
}
