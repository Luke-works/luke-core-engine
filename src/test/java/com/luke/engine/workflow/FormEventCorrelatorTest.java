package com.luke.engine.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.runtime.MessageCorrelationResult;
import org.finos.fluxnova.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

/** The forms→workflow correlator starts form-scoped subscribers and advances waiters,
 *  and never starts a subscription scoped to a different form. */
class FormEventCorrelatorTest {

    private final WorkflowTriggerSubscriptionRepository subs = mock(WorkflowTriggerSubscriptionRepository.class);
    private final RuntimeService runtimeService = mock(RuntimeService.class, Answers.RETURNS_DEEP_STUBS);
    private final IdentityService identityService = mock(IdentityService.class);
    private final FormEventCorrelator correlator = new FormEventCorrelator(subs, runtimeService, identityService);

    private WorkflowTriggerSubscription sub(String processId, String formCode) {
        return new WorkflowTriggerSubscription("t1", "d-" + processId, processId, "forms", "submitted", formCode, 1);
    }

    @Test
    void startsMatchingAndAnyFormSubscribersButNotOtherForms() {
        when(subs.findByTenantIdAndCapabilityAndEventType("t1", "forms", "submitted"))
                .thenReturn(List.of(sub("wf_a_v1", "INTAKE"), sub("wf_b_v1", null), sub("wf_c_v1", "LEAVE")));
        ProcessInstantiationBuilder builder = mock(ProcessInstantiationBuilder.class, Answers.RETURNS_SELF);
        when(runtimeService.createProcessInstanceByKey(anyString())).thenReturn(builder);
        // No waiters.
        when(runtimeService.createMessageCorrelation("forms.submitted").tenantId("t1")
                .setVariables(org.mockito.ArgumentMatchers.anyMap()).correlateAllWithResult())
                .thenReturn(List.<MessageCorrelationResult>of());

        int n = correlator.correlate("t1", "submitted", "INTAKE", "inst-1", "{}");

        // INTAKE (scoped match) + any-form → 2 starts; LEAVE is skipped.
        assertThat(n).isEqualTo(2);
        verify(runtimeService).createProcessInstanceByKey("wf_a_v1");
        verify(runtimeService).createProcessInstanceByKey("wf_b_v1");
        verify(runtimeService, never()).createProcessInstanceByKey("wf_c_v1");
        verify(identityService).clearAuthentication();
    }

    @Test
    void countsResumedWaitersAndSurvivesAStaleSubscription() {
        when(subs.findByTenantIdAndCapabilityAndEventType("t1", "forms", "submitted"))
                .thenReturn(List.of(sub("wf_stale_v1", null)));
        // A stale subscription (undeployed process) throws — must be swallowed, not fatal.
        when(runtimeService.createProcessInstanceByKey("wf_stale_v1"))
                .thenThrow(new RuntimeException("no process definition deployed with key 'wf_stale_v1'"));
        when(runtimeService.createMessageCorrelation("forms.submitted").tenantId("t1")
                .setVariables(org.mockito.ArgumentMatchers.anyMap()).correlateAllWithResult())
                .thenReturn(List.of(mock(MessageCorrelationResult.class)));

        int n = correlator.correlate("t1", "submitted", null, "inst-2", "{}");

        // 0 started (stale skipped) + 1 resumed.
        assertThat(n).isEqualTo(1);
    }
}
