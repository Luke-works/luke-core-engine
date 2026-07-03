package com.luke.engine.capability.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.workflow.CapabilityActionException;
import com.luke.engine.workflow.WorkflowNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests for {@link PhoneActionHandler}: input mapping, placeholder resolution, required number. */
class PhoneActionHandlerTest {

    private final PhoneCallService calls = mock(PhoneCallService.class);
    private final PhoneActionHandler handler = new PhoneActionHandler(calls);

    private WorkflowNode node(Map<String, Object> input) {
        // id, kind, name, capability, action, provider, connection, input, output, task,
        // assignee, onError, next, conditions, else, branches, join, mode, duration, event
        return new WorkflowNode(
                "n1", "action", null, "phone", "call", null, null, input, "out", null,
                null, null, "end", null, null, null, null, null, null, null);
    }

    @Test
    void placesAnOutboundCallResolvingThePlaceholderNumber() {
        PhoneCall call = mock(PhoneCall.class);
        when(call.getId()).thenReturn("call1");
        when(calls.placeOutbound(eq("t1"), eq("workflow"), any())).thenReturn(call);

        Object result = handler.execute(
                "t1",
                node(Map.of("customerNumber", "{{ lead.phone }}", "assistantId", "asst_9")),
                Map.of("lead", Map.of("phone", "+15551234567")));

        ArgumentCaptor<OutboundCallRequest> req = ArgumentCaptor.forClass(OutboundCallRequest.class);
        verify(calls).placeOutbound(eq("t1"), eq("workflow"), req.capture());
        assertThat(req.getValue().customerNumber()).isEqualTo("+15551234567");
        assertThat(req.getValue().assistantId()).isEqualTo("asst_9");
        assertThat(result).isEqualTo(Map.of("callId", "call1"));
    }

    @Test
    void missingNumberIsABusinessError() {
        assertThatThrownBy(() -> handler.execute("t1", node(Map.of("assistantId", "asst_9")), Map.of()))
                .isInstanceOf(CapabilityActionException.class)
                .hasMessageContaining("customerNumber");
    }

    @Test
    void reportsThePhoneCapability() {
        assertThat(handler.capability()).isEqualTo("phone");
    }
}
