package com.luke.engine.capability.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.luke.engine.branding.PlanService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** The plan-tier gate on capability subscribe is default-lenient: OFF passes through unchanged (dev/qa),
 *  ON gates enabling a capability by whether the tenant's plan includes it (402 otherwise). */
class SubscriptionControllerTierGateTest {

    private final CapabilitySubscriptionRepository subs = mock(CapabilitySubscriptionRepository.class);
    private final CapabilityRepository caps = mock(CapabilityRepository.class);
    private final PlanService plan = mock(PlanService.class);
    private final Capability forms =
            new Capability("FORMS", "Forms", "Build and manage forms.", "ListChecks", "/forms", "ACTIVE", "STANDARD");

    private void catalogHasForms() {
        when(caps.findByCode("FORMS")).thenReturn(Optional.of(forms));
        when(subs.findByTenantIdAndCapabilityCode("t1", "FORMS")).thenReturn(Optional.empty());
        when(subs.save(any(CapabilitySubscription.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void gateOffEnablesRegardlessOfPlan() {
        catalogHasForms();
        SubscriptionController controller = new SubscriptionController(subs, caps, plan, false);

        controller.enable("t1", "FORMS");

        verify(subs).save(any(CapabilitySubscription.class));
        verifyNoInteractions(plan); // the gate never consults the plan when off
    }

    @Test
    void gateOnBlocksACapabilityThePlanExcludes() {
        catalogHasForms();
        when(plan.includesCapability("t1", "FORMS")).thenReturn(false);
        SubscriptionController controller = new SubscriptionController(subs, caps, plan, true);

        assertThatThrownBy(() -> controller.enable("t1", "FORMS"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(402));
        verify(subs, never()).save(any());
    }

    @Test
    void gateOnAllowsAnIncludedCapability() {
        catalogHasForms();
        when(plan.includesCapability("t1", "FORMS")).thenReturn(true);
        SubscriptionController controller = new SubscriptionController(subs, caps, plan, true);

        controller.enable("t1", "FORMS");

        verify(subs).save(any(CapabilitySubscription.class));
    }
}
