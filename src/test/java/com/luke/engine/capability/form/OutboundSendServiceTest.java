package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.email.EmailMessage;
import com.luke.engine.capability.email.EmailRequest;
import com.luke.engine.capability.email.EmailService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Outbound send creates a prefilled SENT instance + a /respond link and mails the recipient;
 *  guards on kind/published/email; email failure never fails the send. */
class OutboundSendServiceTest {

    private final FormDefinitionRepository forms = mock(FormDefinitionRepository.class);
    private final FormInstanceRepository instances = mock(FormInstanceRepository.class);
    private final EmailService emails = mock(EmailService.class);
    private final FormEventPublisher events = mock(FormEventPublisher.class);
    private final OutboundSendService service =
            new OutboundSendService(forms, instances, emails, events, "http://localhost:5173/");

    private FormDefinition form(String kind, Integer published) {
        FormDefinition f = new FormDefinition();
        f.setId("f1");
        f.setTenantId("t1");
        f.setCode("FM-1");
        f.setName("Intake");
        f.setKind(kind);
        f.setPublishedVersion(published);
        return f;
    }

    private final Map<String, Object> recipient = Map.of("firstName", "Jo", "email", "jo@acme.com");

    @Test
    void createsSentInstanceAndMailsLink() {
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(form(FormDefinition.KIND_OUTBOUND, 2)));
        when(instances.existsByToken(anyString())).thenReturn(false);
        when(instances.save(any())).thenAnswer(a -> a.getArgument(0));
        EmailMessage msg = mock(EmailMessage.class);
        when(msg.getStatus()).thenReturn("SENT");
        when(emails.sendRaw(eq("t1"), any(), any(EmailRequest.class))).thenReturn(msg);

        OutboundSendService.SendResult r = service.send("t1", "f1", recipient, Map.of("amount", "10"), null, "u1");

        assertThat(r.emailStatus()).isEqualTo("SENT");
        assertThat(r.token()).startsWith("inv_");
        assertThat(r.link()).isEqualTo("http://localhost:5173/respond/" + r.token());
        // The saved instance is SENT, prefilled, and carries the recipient.
        org.mockito.ArgumentCaptor<FormInstance> captor = org.mockito.ArgumentCaptor.forClass(FormInstance.class);
        verify(instances).save(captor.capture());
        FormInstance saved = captor.getValue();
        assertThat(saved.getState()).isEqualTo(FormInstanceStates.SENT);
        assertThat(saved.getVersion()).isEqualTo(2);
        assertThat(saved.getPrefill()).containsEntry("amount", "10");
        assertThat(saved.getRecipient()).containsEntry("email", "jo@acme.com");
        verify(events).emit(any(), eq("sent"));
    }

    @Test
    void emailFailureStillReturnsTheLink() {
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(form(FormDefinition.KIND_OUTBOUND, 1)));
        when(instances.existsByToken(anyString())).thenReturn(false);
        when(instances.save(any())).thenAnswer(a -> a.getArgument(0));
        when(emails.sendRaw(anyString(), any(), any(EmailRequest.class)))
                .thenThrow(new RuntimeException("no email server configured"));

        OutboundSendService.SendResult r = service.send("t1", "f1", recipient, null, null, "u1");

        assertThat(r.emailStatus()).isEqualTo("FAILED");
        assertThat(r.link()).contains("/respond/inv_");
        verify(instances).save(any()); // instance still created
    }

    @Test
    void rejectsInboundForm() {
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(form(FormDefinition.KIND_INBOUND, 1)));
        assertThatThrownBy(() -> service.send("t1", "f1", recipient, null, null, "u1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("outbound");
    }

    @Test
    void rejectsUnpublishedForm() {
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(form(FormDefinition.KIND_OUTBOUND, null)));
        assertThatThrownBy(() -> service.send("t1", "f1", recipient, null, null, "u1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Publish");
    }

    @Test
    void rejectsMissingRecipientEmail() {
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(form(FormDefinition.KIND_OUTBOUND, 1)));
        assertThatThrownBy(() -> service.send("t1", "f1", Map.of("firstName", "Jo"), null, null, "u1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("email");
    }
}
