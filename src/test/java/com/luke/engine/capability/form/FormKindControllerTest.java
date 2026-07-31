package com.luke.engine.capability.form;

import com.luke.engine.branding.PlanFeatures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Form-kind creation + the kind-guarded config endpoints (submission handling / outbound roles). */
class FormKindControllerTest {

    private final FormDefinitionRepository forms = mock(FormDefinitionRepository.class);
    private final FormVersionRepository versions = mock(FormVersionRepository.class);
    private final FormAuditEventRepository audit = mock(FormAuditEventRepository.class);
    private final EmbedTokens tokens = mock(EmbedTokens.class);
    private final com.luke.engine.tenant.UserDirectory dir = mock(com.luke.engine.tenant.UserDirectory.class);
    private final com.luke.engine.branding.TenantPlanRepository plans =
            mock(com.luke.engine.branding.TenantPlanRepository.class);
    private final com.luke.engine.branding.BrandingPolicy branding = new com.luke.engine.branding.BrandingPolicy(plans);
    private final FormEmbedSiteRepository embedSites = mock(FormEmbedSiteRepository.class);
    private final FormDefinitionController controller =
            new FormDefinitionController(forms, versions, audit, tokens, dir, branding, new PlanFeatures(plans), embedSites);

    private FormDefinition stored(String kind) {
        FormDefinition f = new FormDefinition();
        f.setId("f1");
        f.setTenantId("t1");
        f.setCode("FM-1");
        f.setName("n");
        f.setKind(kind);
        return f;
    }

    @Test
    void createDefaultsToInboundAndHonoursOutbound() {
        when(forms.existsByTenantIdAndCode(anyString(), anyString())).thenReturn(false);
        when(forms.save(any())).thenAnswer(a -> a.getArgument(0));

        FormDefinition inbound = controller.create("t1", "u1", new FormDefinitionController.CreateForm("A", null, null));
        assertThat(inbound.getKind()).isEqualTo(FormDefinition.KIND_INBOUND);

        FormDefinition outbound = controller.create("t1", "u1", new FormDefinitionController.CreateForm("B", null, "outbound"));
        assertThat(outbound.getKind()).isEqualTo(FormDefinition.KIND_OUTBOUND);
    }

    @Test
    void submissionHandlingIsInboundOnlyAndDefaultsToCollect() {
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(stored(FormDefinition.KIND_INBOUND)));
        when(forms.save(any())).thenAnswer(a -> a.getArgument(0));

        FormDefinition out = controller.setSubmissionHandling("t1", "u1", "f1",
                new FormDefinitionController.SubmissionHandlingBody(null));
        assertThat(out.getSubmissionHandling()).isEqualTo("COLLECT");

        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(stored(FormDefinition.KIND_OUTBOUND)));
        assertThatThrownBy(() -> controller.setSubmissionHandling("t1", "u1", "f1",
                new FormDefinitionController.SubmissionHandlingBody("COLLECT")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("inbound");
    }

    @Test
    void outboundConfigValidatesRolesAndStoresJson() {
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(stored(FormDefinition.KIND_OUTBOUND)));
        when(forms.save(any())).thenAnswer(a -> a.getArgument(0));

        FormDefinition out = controller.setOutboundConfig("t1", "u1", "f1",
                new FormDefinitionController.OutboundConfigBody(Map.of("amount", "PREPARER", "note", "RECIPIENT")));
        assertThat(out.getOutboundRolesJson()).contains("PREPARER").contains("RECIPIENT");

        assertThatThrownBy(() -> controller.setOutboundConfig("t1", "u1", "f1",
                new FormDefinitionController.OutboundConfigBody(Map.of("x", "SOMEONE_ELSE"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid role");
    }

    @Test
    void outboundConfigRejectedForInboundForm() {
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(stored(FormDefinition.KIND_INBOUND)));
        assertThatThrownBy(() -> controller.setOutboundConfig("t1", "u1", "f1",
                new FormDefinitionController.OutboundConfigBody(Map.of("x", "PREPARER"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("outbound");
    }
}
