package com.luke.engine.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.audit.AdminAuditService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/** The operator-only plan endpoint: FREE is stored as the ABSENCE of a row, and only the two known
 *  plan values are accepted (a typo must never read as "paid"). */
class TenantPlanControllerTest {

    private final TenantPlanRepository plans = mock(TenantPlanRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final TenantPlanController controller = new TenantPlanController(plans, audit);

    @Test
    void unknownTenantReadsAsFree() {
        when(plans.findById("t1")).thenReturn(Optional.empty());
        Map<String, Object> out = controller.get("t1");
        assertThat(out.get("plan")).isEqualTo(TenantPlan.PLAN_FREE);
        assertThat(out.get("canHideBadge")).isEqualTo(false);
    }

    @Test
    void settingPaidStoresTheRowAndUnlocksHiding() {
        when(plans.findById("t1")).thenReturn(Optional.empty());
        when(plans.save(any(TenantPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> out = controller.set("t1", "operator-1",
                new TenantPlanController.PlanBody("paid", "  contract #42  "));

        ArgumentCaptor<TenantPlan> saved = ArgumentCaptor.forClass(TenantPlan.class);
        verify(plans).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo("t1");
        assertThat(saved.getValue().getPlan()).isEqualTo(TenantPlan.PLAN_PAID); // case-normalized
        assertThat(saved.getValue().getNote()).isEqualTo("contract #42");
        assertThat(out.get("canHideBadge")).isEqualTo(true);
    }

    @Test
    void settingFreeDeletesTheRowRatherThanStoringFree() {
        TenantPlan existing = new TenantPlan("t1", TenantPlan.PLAN_PAID);
        when(plans.findById("t1")).thenReturn(Optional.of(existing));

        Map<String, Object> out = controller.set("t1", "operator-1",
                new TenantPlanController.PlanBody("FREE", null));

        verify(plans).delete(existing);
        verify(plans, never()).save(any());
        assertThat(out.get("plan")).isEqualTo(TenantPlan.PLAN_FREE);
        assertThat(out.get("canHideBadge")).isEqualTo(false);
    }

    @Test
    void downgradingATenantWithNoRowIsANoOp() {
        when(plans.findById("t1")).thenReturn(Optional.empty());
        controller.set("t1", "operator-1", new TenantPlanController.PlanBody("FREE", null));
        verify(plans, never()).delete(any());
    }

    @Test
    void anUnknownPlanIsRejected() {
        assertThatThrownBy(() -> controller.set("t1", "op",
                new TenantPlanController.PlanBody("ENTERPRISE", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("plan must be");
        assertThatThrownBy(() -> controller.set("t1", "op", new TenantPlanController.PlanBody(null, null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(plans, never()).save(any());
        verify(plans, never()).delete(any());
    }

    @Test
    void everyPlanChangeIsAudited() {
        when(plans.findById("t1")).thenReturn(Optional.empty());
        when(plans.save(any(TenantPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        controller.set("t1", "operator-1", new TenantPlanController.PlanBody("PAID", null));
        verify(audit).record("tenant.plan.set", "tenant", "t1", "t1", "operator-1", true,
                Map.of("plan", TenantPlan.PLAN_PAID));
    }
}
