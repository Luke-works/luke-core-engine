package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luke.engine.branding.BrandingPolicy;
import com.luke.engine.branding.PlanFeatures;
import com.luke.engine.branding.TenantPlanRepository;
import com.luke.engine.payments.PaymentAccountService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A form that takes a payment can only go live when the payment is sound AND the tenant can take it. */
class FormPaymentPublishGateTest {

    private static final String PAID = """
            {"root":["pay"],"entities":{"pay":{"id":"pay","type":"payment","attributes":{"key":"pay",
              "amountMode":"fixed","amountMinor":500,"currency":"USD"}}}}""";
    private static final String BROKEN = """
            {"root":["pay"],"entities":{"pay":{"id":"pay","type":"payment","attributes":{"key":"pay","amountMode":"fixed"}}}}""";
    private static final String FREE_FORM = """
            {"root":["n"],"entities":{"n":{"id":"n","type":"textField","attributes":{"key":"n"}}}}""";

    private final FormDefinitionRepository forms = mock(FormDefinitionRepository.class);
    private final FormVersionRepository versions = mock(FormVersionRepository.class);
    private final PaymentAccountService payments = mock(PaymentAccountService.class);
    private final TenantPlanRepository plans = mock(TenantPlanRepository.class);

    private FormDefinitionController controller(PaymentAccountService p) {
        return new FormDefinitionController(forms, versions, mock(FormAuditEventRepository.class), mock(EmbedTokens.class),
                mock(com.luke.engine.tenant.UserDirectory.class), new BrandingPolicy(plans), new PlanFeatures(plans),
                mock(FormEmbedSiteRepository.class), p);
    }

    private void stubForm(String schema) {
        FormDefinition f = new FormDefinition();
        f.setTenantId("t1");
        f.setCode("F");
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(f));
        FormVersion v = new FormVersion("f1", 1, schema, "u");
        v.setSignedOffAt(LocalDateTime.now());
        when(versions.findByFormIdAndVersion(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(Optional.of(v));
        when(forms.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static HttpStatus statusOf(Runnable r) {
        try {
            r.run();
            return HttpStatus.OK;
        } catch (ResponseStatusException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
    }

    @Test
    void aFormWithoutAPaymentIsUnaffected() {
        stubForm(FREE_FORM);
        assertThat(statusOf(() -> controller(null).publish("t1", "u", "f1", 1))).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aMisconfiguredPaymentIsRefused() {
        stubForm(BROKEN);
        when(payments.ready("t1")).thenReturn(true);
        assertThatThrownBy(() -> controller(payments).publish("t1", "u", "f1", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(422);
                    assertThat(e.getReason()).contains("Set the payment amount").contains("currency");
                });
    }

    @Test
    void aTenantThatCantTakePaymentsCantPublishOne() {
        stubForm(PAID);
        when(payments.ready("t1")).thenReturn(false);
        when(payments.planAllows("t1")).thenReturn(true);
        assertThatThrownBy(() -> controller(payments).publish("t1", "u", "f1", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(409);
                    assertThat(e.getReason()).contains("Connect a Stripe account");
                });
        when(payments.planAllows("t1")).thenReturn(false);
        assertThatThrownBy(() -> controller(payments).publish("t1", "u", "f1", 1))
                .hasMessageContaining("paid plan");
        // Unwired (no payments module): refused, never published unpaid.
        assertThat(statusOf(() -> controller(null).publish("t1", "u", "f1", 1))).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void pinningEmbedsToAVersionIsGatedLikePublishing() {
        stubForm(BROKEN);
        FormDefinition f = forms.findByIdAndTenantId("f1", "t1").orElseThrow();
        f.setPublishedVersion(1);
        f.setKind(FormDefinition.KIND_INBOUND);
        f.setSubmissionHandling("PROCESS");
        when(payments.ready("t1")).thenReturn(true);
        var body = new FormDefinitionController.EmbedVersionBody("PINNED", 1);
        assertThat(statusOf(() -> controller(payments).setEmbedVersion("t1", "u", "f1", body)))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        when(payments.ready("t1")).thenReturn(false);
        stubForm(PAID);
        FormDefinition g = forms.findByIdAndTenantId("f1", "t1").orElseThrow();
        g.setPublishedVersion(1);
        g.setKind(FormDefinition.KIND_INBOUND);
        g.setSubmissionHandling("PROCESS");
        assertThat(statusOf(() -> controller(payments).setEmbedVersion("t1", "u", "f1", body))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(g.getEmbedVersion()).isNull();
    }

    @Test
    void theFieldContractSaysWhetherTheFormTakesAPayment() {
        stubForm(PAID);
        FormDefinition f = forms.findByIdAndTenantId("f1", "t1").orElseThrow();
        f.setPublishedVersion(1);
        when(forms.findByTenantIdAndCode("t1", "F")).thenReturn(Optional.of(f));
        var out = controller(payments).fields("t1", "F", "published");
        assertThat(out.get("takesPayment")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var fields = (java.util.List<java.util.Map<String, Object>>) out.get("fields");
        assertThat(fields).singleElement().satisfies(x -> assertThat(x.get("type")).isEqualTo("Json"));
        stubForm(FREE_FORM);
        f = forms.findByIdAndTenantId("f1", "t1").orElseThrow();
        f.setPublishedVersion(1);
        when(forms.findByTenantIdAndCode("t1", "F")).thenReturn(Optional.of(f));
        assertThat(controller(payments).fields("t1", "F", "published").get("takesPayment")).isEqualTo(false);
    }

    @Test
    void aReadyTenantPublishes() {
        stubForm(PAID);
        when(payments.ready("t1")).thenReturn(true);
        assertThat(controller(payments).publish("t1", "u", "f1", 1).getPublishedVersion()).isEqualTo(1);
    }
}
