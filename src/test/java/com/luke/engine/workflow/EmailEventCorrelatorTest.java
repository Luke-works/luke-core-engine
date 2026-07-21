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

/** The inbound-email→workflow correlator starts box-scoped (and any-box) subscribers subscribed
 *  to email.inbound, advances waiters, and never starts a subscription scoped to a different box. */
class EmailEventCorrelatorTest {

    private final WorkflowTriggerSubscriptionRepository subs = mock(WorkflowTriggerSubscriptionRepository.class);
    private final RuntimeService runtimeService = mock(RuntimeService.class, Answers.RETURNS_DEEP_STUBS);
    private final IdentityService identityService = mock(IdentityService.class);
    private final EmailEventCorrelator correlator = new EmailEventCorrelator(subs, runtimeService, identityService);

    /** formCode is reused as the box-address scope for email subscriptions. */
    private WorkflowTriggerSubscription sub(String processId, String boxAddress) {
        return new WorkflowTriggerSubscription("t1", "d-" + processId, processId, "email", "inbound", boxAddress, 1);
    }

    @Test
    void startsMatchingAndAnyBoxSubscribersButNotOtherBoxes() {
        when(subs.findByTenantIdAndCapabilityAndEventType("t1", "email", "inbound"))
                .thenReturn(List.of(
                        sub("wf_support_v1", "support@acme.lukeflow.com"),
                        sub("wf_any_v1", null),
                        sub("wf_sales_v1", "sales@acme.lukeflow.com")));
        ProcessInstantiationBuilder builder = mock(ProcessInstantiationBuilder.class, Answers.RETURNS_SELF);
        when(runtimeService.createProcessInstanceByKey(anyString())).thenReturn(builder);
        when(runtimeService.createMessageCorrelation("email.inbound").tenantId("t1")
                .setVariables(org.mockito.ArgumentMatchers.anyMap()).correlateAllWithResult())
                .thenReturn(List.<MessageCorrelationResult>of());

        int n = correlator.correlate("t1", "support@acme.lukeflow.com", "msg-1",
                "sender@example.com", "Hello", "{}");

        // support (scoped match) + any-box → 2 starts; sales is skipped.
        assertThat(n).isEqualTo(2);
        verify(runtimeService).createProcessInstanceByKey("wf_support_v1");
        verify(runtimeService).createProcessInstanceByKey("wf_any_v1");
        verify(runtimeService, never()).createProcessInstanceByKey("wf_sales_v1");
        verify(identityService).clearAuthentication();
    }

    @Test
    void countsResumedWaitersAndSurvivesAStaleSubscription() {
        when(subs.findByTenantIdAndCapabilityAndEventType("t1", "email", "inbound"))
                .thenReturn(List.of(sub("wf_stale_v1", null)));
        when(runtimeService.createProcessInstanceByKey("wf_stale_v1"))
                .thenThrow(new RuntimeException("no process definition deployed with key 'wf_stale_v1'"));
        when(runtimeService.createMessageCorrelation("email.inbound").tenantId("t1")
                .setVariables(org.mockito.ArgumentMatchers.anyMap()).correlateAllWithResult())
                .thenReturn(List.of(mock(MessageCorrelationResult.class)));

        int n = correlator.correlate("t1", "support@acme.lukeflow.com", "msg-2", "s@x.com", "Hi", "{}");

        // 0 started (stale skipped) + 1 resumed.
        assertThat(n).isEqualTo(1);
    }
}
