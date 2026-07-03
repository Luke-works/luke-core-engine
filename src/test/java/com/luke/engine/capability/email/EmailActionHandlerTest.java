package com.luke.engine.capability.email;

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

/** Tests for {@link EmailActionHandler}: input mapping, placeholder resolution, raw vs template. */
class EmailActionHandlerTest {

    private final EmailService emails = mock(EmailService.class);
    private final EmailActionHandler handler = new EmailActionHandler(emails);

    private WorkflowNode node(Map<String, Object> input) {
        // id, kind, name, capability, action, provider, connection, input, output, task,
        // assignee, onError, next, conditions, else, branches, join, mode, duration, event
        return new WorkflowNode(
                "n1", "action", null, "email", "send", null, null, input, "out", null,
                null, null, "end", null, null, null, null, null, null, null);
    }

    private EmailMessage stubMessage() {
        EmailMessage msg = mock(EmailMessage.class);
        when(msg.getId()).thenReturn("em1");
        return msg;
    }

    @Test
    void rawSendResolvesPlaceholdersAndCallsSendRaw() {
        EmailMessage msg = stubMessage();
        when(emails.sendRaw(eq("t1"), eq("workflow"), any())).thenReturn(msg);

        Object result = handler.execute(
                "t1",
                node(Map.of("to", "{{ submission.email }}", "subject", "Hi {{ name }}", "htmlBody", "<p>x</p>")),
                Map.of("submission", Map.of("email", "a@b.com"), "name", "Sam"));

        ArgumentCaptor<EmailRequest> req = ArgumentCaptor.forClass(EmailRequest.class);
        verify(emails).sendRaw(eq("t1"), eq("workflow"), req.capture());
        assertThat(req.getValue().to()).isEqualTo("a@b.com");
        assertThat(req.getValue().subject()).isEqualTo("Hi Sam");
        assertThat(result).isEqualTo(Map.of("emailId", "em1"));
    }

    @Test
    void templateSendUsesSendTemplate() {
        EmailMessage msg = stubMessage();
        when(emails.sendTemplate(eq("t1"), eq("workflow"), any())).thenReturn(msg);

        handler.execute("t1", node(Map.of("to", "a@b.com", "template", "welcome")), Map.of());

        ArgumentCaptor<EmailRequest> req = ArgumentCaptor.forClass(EmailRequest.class);
        verify(emails).sendTemplate(eq("t1"), eq("workflow"), req.capture());
        assertThat(req.getValue().templateAlias()).isEqualTo("welcome");
    }

    @Test
    void missingRecipientIsABusinessError() {
        assertThatThrownBy(() -> handler.execute("t1", node(Map.of("subject", "x")), Map.of()))
                .isInstanceOf(CapabilityActionException.class)
                .hasMessageContaining("to");
    }

    @Test
    void reportsTheEmailCapability() {
        assertThat(handler.capability()).isEqualTo("email");
    }
}
