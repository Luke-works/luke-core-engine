package com.luke.engine.capability.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.signature.SignatureInstanceService.CampaignInput;
import com.luke.engine.workflow.CapabilityActionException;
import com.luke.engine.workflow.WorkflowNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests for {@link SignActionHandler}: definition/recipient mapping, placeholder resolution, guards. */
class SignActionHandlerTest {

    private final SignatureInstanceService instances = mock(SignatureInstanceService.class);
    private final SignActionHandler handler = new SignActionHandler(instances);

    private WorkflowNode node(Map<String, Object> input) {
        // id, kind, name, capability, action, provider, connection, input, output, task,
        // assignee, onError, next, conditions, else, branches, join, mode, duration, event
        return new WorkflowNode(
                "n1", "action", null, "signatures", "send", null, null, input, "out", null,
                null, null, "end", null, null, null, null, null, null, null);
    }

    @Test
    void startsACampaignMappingRecipientsAndResolvingPlaceholders() {
        SignatureInstance inst = mock(SignatureInstance.class);
        when(inst.getId()).thenReturn("inst1");
        when(instances.startCampaign(eq("t1"), eq("workflow"), any())).thenReturn(inst);

        Map<String, Object> input = Map.of(
                "definitionCode", "SIG-1",
                "recipients", List.of(Map.of("signerId", "s1", "name", "{{ lead.name }}", "email", "{{ lead.email }}")));

        Object result = handler.execute("t1", node(input), Map.of("lead", Map.of("name", "Sam", "email", "sam@x.com")));

        ArgumentCaptor<CampaignInput> req = ArgumentCaptor.forClass(CampaignInput.class);
        verify(instances).startCampaign(eq("t1"), eq("workflow"), req.capture());
        assertThat(req.getValue().definitionCode()).isEqualTo("SIG-1");
        assertThat(req.getValue().recipients()).hasSize(1);
        assertThat(req.getValue().recipients().get(0).email()).isEqualTo("sam@x.com");
        assertThat(req.getValue().recipients().get(0).name()).isEqualTo("Sam");
        assertThat(result).isEqualTo(Map.of("instanceId", "inst1"));
    }

    @Test
    void missingDefinitionCodeIsABusinessError() {
        assertThatThrownBy(() -> handler.execute("t1", node(Map.of("recipients", List.of(Map.of("email", "a@b.com")))), Map.of()))
                .isInstanceOf(CapabilityActionException.class)
                .hasMessageContaining("definitionCode");
    }

    @Test
    void missingRecipientsIsABusinessError() {
        assertThatThrownBy(() -> handler.execute("t1", node(Map.of("definitionCode", "SIG-1")), Map.of()))
                .isInstanceOf(CapabilityActionException.class)
                .hasMessageContaining("recipient");
    }

    @Test
    void reportsTheSignaturesCapability() {
        assertThat(handler.capability()).isEqualTo("signatures");
    }
}
