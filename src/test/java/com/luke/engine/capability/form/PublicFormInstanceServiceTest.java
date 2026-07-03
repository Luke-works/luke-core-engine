package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.email.EmailMessage;
import com.luke.engine.capability.email.EmailRequest;
import com.luke.engine.capability.email.EmailService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** The public OTP-gated fill surface: request → verify → render/submit, with the security guards
 *  (single-use code, attempt cap, access token must match the instance token). */
class PublicFormInstanceServiceTest {

    private final FormInstanceRepository instances = mock(FormInstanceRepository.class);
    private final FormRecipientOtpRepository otps = mock(FormRecipientOtpRepository.class);
    private final FormDefinitionRepository forms = mock(FormDefinitionRepository.class);
    private final FormVersionRepository versions = mock(FormVersionRepository.class);
    private final EmailService emails = mock(EmailService.class);
    private final FormSubmissionService submissions = mock(FormSubmissionService.class);
    private final RecipientAccessTokens accessTokens = mock(RecipientAccessTokens.class);
    private final PublicFormInstanceService service = new PublicFormInstanceService(
            instances, otps, forms, versions, emails, submissions, accessTokens);

    private FormInstance instance(String state) {
        FormInstance i = new FormInstance();
        i.setId("i1");
        i.setTenantId("t1");
        i.setToken("inv_abc");
        i.setDefinitionCode("FM-1");
        i.setVersion(1);
        i.setState(state);
        i.setRecipient(Map.of("firstName", "Jo", "email", "jo@acme.com"));
        return i;
    }

    private FormRecipientOtp otp(String code, String salt, int attempts, LocalDateTime expiresAt) {
        FormRecipientOtp o = new FormRecipientOtp();
        o.setInstanceId("i1");
        o.setCodeHash(PublicFormInstanceService.hash(code, salt));
        o.setCodeSalt(salt);
        o.setAttempts(attempts);
        o.setExpiresAt(expiresAt);
        return o;
    }

    @Test
    void requestOtpStoresAndMails() {
        when(instances.findByToken("inv_abc")).thenReturn(Optional.of(instance(FormInstanceStates.SENT)));
        when(otps.findByInstanceId("i1")).thenReturn(Optional.empty());
        EmailMessage msg = mock(EmailMessage.class);
        when(msg.getStatus()).thenReturn("SENT");
        when(emails.sendRaw(eq("t1"), any(), any(EmailRequest.class))).thenReturn(msg);

        Map<String, Object> out = service.requestOtp("inv_abc");

        assertThat(out).containsEntry("emailStatus", "SENT");
        assertThat(out.get("sentTo").toString()).contains("***");
        verify(otps).save(any(FormRecipientOtp.class));
    }

    @Test
    void verifyWrongCodeIncrementsAttempts_correctCodeOpensAndMintsToken() {
        FormInstance inst = instance(FormInstanceStates.SENT);
        when(instances.findByToken("inv_abc")).thenReturn(Optional.of(inst));
        when(otps.findByInstanceId("i1")).thenReturn(Optional.of(otp("123456", "s", 0, LocalDateTime.now().plusMinutes(5))));

        assertThatThrownBy(() -> service.verify("inv_abc", "000000"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("isn't right");

        when(accessTokens.sign(eq("inv_abc"), anyLong())).thenReturn("acc-tok");
        Map<String, Object> out = service.verify("inv_abc", "123456");
        assertThat(out).containsEntry("accessToken", "acc-tok");
        assertThat(inst.getState()).isEqualTo(FormInstanceStates.OPENED);
        verify(otps).delete(any(FormRecipientOtp.class)); // single-use
    }

    @Test
    void verifyRejectsExpiredCode() {
        when(instances.findByToken("inv_abc")).thenReturn(Optional.of(instance(FormInstanceStates.SENT)));
        when(otps.findByInstanceId("i1")).thenReturn(Optional.of(otp("123456", "s", 0, LocalDateTime.now().minusMinutes(1))));
        assertThatThrownBy(() -> service.verify("inv_abc", "123456"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("expired");
    }

    @Test
    void renderRequiresAnAccessTokenMatchingTheInstance() {
        when(instances.findByToken("inv_abc")).thenReturn(Optional.of(instance(FormInstanceStates.OPENED)));
        // Access token authorizes a DIFFERENT instance token → rejected.
        when(accessTokens.verify(eq("acc-tok"), anyLong())).thenReturn("inv_OTHER");
        assertThatThrownBy(() -> service.render("inv_abc", "acc-tok"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Verify your email");
    }

    @Test
    void renderReturnsSchemaAndPrefillForAValidSession() {
        FormInstance inst = instance(FormInstanceStates.OPENED);
        inst.setPrefill(Map.of("amount", "10"));
        when(instances.findByToken("inv_abc")).thenReturn(Optional.of(inst));
        when(accessTokens.verify(eq("acc-tok"), anyLong())).thenReturn("inv_abc");
        FormDefinition form = new FormDefinition();
        form.setId("f1");
        form.setCode("FM-1");
        form.setName("Intake");
        when(forms.findByTenantIdAndCode("t1", "FM-1")).thenReturn(Optional.of(form));
        FormVersion v = mock(FormVersion.class);
        when(v.getSchema()).thenReturn("{\"entities\":{},\"root\":[]}");
        when(versions.findByFormIdAndVersion("f1", 1)).thenReturn(Optional.of(v));

        Map<String, Object> out = service.render("inv_abc", "acc-tok");
        assertThat(out).containsEntry("code", "FM-1").containsEntry("version", 1);
        assertThat(out.get("schema").toString()).contains("entities");
        assertThat(out).containsKey("prefill");
    }

    @Test
    void submitDelegatesToFormSubmissionService() {
        FormInstance inst = instance(FormInstanceStates.IN_PROGRESS);
        when(instances.findByToken("inv_abc")).thenReturn(Optional.of(inst));
        when(accessTokens.verify(eq("acc-tok"), anyLong())).thenReturn("inv_abc");

        service.submit("inv_abc", "acc-tok", Map.of("note", "hi"));
        verify(submissions).submit(eq(inst), eq(Map.of("note", "hi")));
    }
}
