package com.luke.engine.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan gating must fail CLOSED. Handing out a paid feature because a billing lookup blipped is the
 * expensive mistake; briefly refusing one a paying tenant is entitled to is visible and recoverable.
 */
class PlanFeaturesTest {

    private final TenantPlanRepository plans = mock(TenantPlanRepository.class);
    private final PlanFeatures features = new PlanFeatures(plans);

    @Test
    @DisplayName("a paid tenant may use attachments")
    void paid() {
        when(plans.findById("t1")).thenReturn(Optional.of(new TenantPlan("t1", TenantPlan.PLAN_PAID)));
        assertThat(features.canUseAttachments("t1")).isTrue();
    }

    @Test
    @DisplayName("no plan row means the free plan — attachments refused")
    void noRowIsFree() {
        when(plans.findById("t1")).thenReturn(Optional.empty());
        assertThat(features.canUseAttachments("t1")).isFalse();
    }

    @Test
    @DisplayName("an explicit free plan is refused")
    void explicitlyFree() {
        when(plans.findById("t1")).thenReturn(Optional.of(new TenantPlan("t1", TenantPlan.PLAN_FREE)));
        assertThat(features.canUseAttachments("t1")).isFalse();
    }

    @Test
    @DisplayName("a blank or null tenant is refused rather than defaulting open")
    void blankTenant() {
        assertThat(features.canUseAttachments(null)).isFalse();
        assertThat(features.canUseAttachments("  ")).isFalse();
    }

    @Test
    @DisplayName("an unreachable plan table refuses, and never propagates the failure")
    void lookupFailureFailsClosed() {
        // A billing outage must not silently unlock a paid feature for every tenant at once.
        when(plans.findById("t1")).thenThrow(new IllegalStateException("db down"));
        assertThat(features.canUseAttachments("t1")).isFalse();
    }
}
