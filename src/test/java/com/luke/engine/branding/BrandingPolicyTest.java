package com.luke.engine.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The badge rule: free tenants ALWAYS show "Developed at Lukeflow" (that is the marketing); paying
 * tenants choose, defaulting to shown. Everything unexpected fails CLOSED (badge visible).
 */
class BrandingPolicyTest {

    private final TenantPlanRepository plans = mock(TenantPlanRepository.class);
    private final BrandingPolicy policy = new BrandingPolicy(plans);

    private void plan(String tenantId, String planCode) {
        when(plans.findById(tenantId)).thenReturn(Optional.of(new TenantPlan(tenantId, planCode)));
    }

    @Test
    void freeTenantCannotHideTheBadge() {
        when(plans.findById("free")).thenReturn(Optional.empty()); // no row = FREE
        assertThat(policy.canHideBadge("free")).isFalse();
        assertThat(policy.planOf("free")).isEqualTo(TenantPlan.PLAN_FREE);
    }

    @Test
    void paidTenantCanHideTheBadge() {
        plan("paid", TenantPlan.PLAN_PAID);
        assertThat(policy.canHideBadge("paid")).isTrue();
        assertThat(policy.planOf("paid")).isEqualTo(TenantPlan.PLAN_PAID);
    }

    @Test
    void anExplicitFreeRowStillCannotHide() {
        plan("downgraded", TenantPlan.PLAN_FREE);
        assertThat(policy.canHideBadge("downgraded")).isFalse();
    }

    @Test
    void freeTenantShowsTheBadgeEvenWhenTheFormSaysOtherwise() {
        when(plans.findById("free")).thenReturn(Optional.empty());
        // The stored per-form preference is IGNORED while the tenant is free — this is the whole point:
        // a form that had the badge hidden on a paid plan gets it back on downgrade, without us
        // rewriting their setting.
        assertThat(policy.showBadge("free", false)).isTrue();
        assertThat(policy.showBadge("free", true)).isTrue();
        assertThat(policy.showBadge("free", null)).isTrue();
    }

    @Test
    void paidTenantsFormPreferenceIsHonoured() {
        plan("paid", TenantPlan.PLAN_PAID);
        assertThat(policy.showBadge("paid", false)).isFalse();
        assertThat(policy.showBadge("paid", true)).isTrue();
    }

    @Test
    void paidTenantDefaultsToShownWhenNeverSet() {
        plan("paid", TenantPlan.PLAN_PAID);
        assertThat(policy.showBadge("paid", null)).isTrue();
    }

    @Test
    void missingTenantFailsClosed() {
        assertThat(policy.canHideBadge(null)).isFalse();
        assertThat(policy.canHideBadge("  ")).isFalse();
        assertThat(policy.showBadge(null, false)).isTrue();
        assertThat(policy.planOf(null)).isEqualTo(TenantPlan.PLAN_FREE);
    }

    @Test
    void anUnreachablePlanStoreKeepsTheBadgeVisibleInsteadOfFailingTheRender() {
        when(plans.findById(anyString())).thenThrow(new RuntimeException("db down"));
        assertThat(policy.canHideBadge("paid")).isFalse();
        assertThat(policy.showBadge("paid", false)).isTrue();
    }
}
