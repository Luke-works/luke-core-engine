package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luke.engine.branding.BrandingPolicy;
import com.luke.engine.branding.TenantPlanRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Named embed sites through the authoring API. The property that matters is that a label is ALWAYS
 * anchored to the allowlist actually being enforced — removing a site must take its name with it,
 * rather than leaving a name describing an origin nobody may frame from.
 */
class FormEmbedSiteNamesApiTest {

    private final FormDefinitionRepository forms = mock(FormDefinitionRepository.class);
    private final FormVersionRepository versions = mock(FormVersionRepository.class);
    private final FormAuditEventRepository audit = mock(FormAuditEventRepository.class);
    private final EmbedTokens tokens = mock(EmbedTokens.class);
    private final com.luke.engine.tenant.UserDirectory dir = mock(com.luke.engine.tenant.UserDirectory.class);
    private final TenantPlanRepository plans = mock(TenantPlanRepository.class);
    private final FormEmbedSiteRepository embedSites = mock(FormEmbedSiteRepository.class);
    private final FormDefinitionController controller =
            new FormDefinitionController(forms, versions, audit, tokens, dir, new BrandingPolicy(plans), embedSites);

    private FormDefinition form;

    @BeforeEach
    void setUp() {
        form = new FormDefinition();
        form.setId("f1");
        form.setTenantId("t1");
        form.setCode("FM-1");
        form.setName("n");
        form.setKind(FormDefinition.KIND_INBOUND);
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(form));
        when(forms.save(any(FormDefinition.class))).thenAnswer(i -> i.getArgument(0));
        when(plans.findById("t1")).thenReturn(Optional.empty());
    }

    private static Map<String, String> names(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    private FormDefinition patch(String origins, Map<String, String> labels) {
        return controller.patchMeta("t1", "u1", "f1",
                new FormDefinitionController.MetaPatch(null, null, origins, labels, null));
    }

    @Test
    @DisplayName("stores names alongside the allowlist")
    void storesNames() {
        FormDefinition saved = patch("https://acme.com\nhttps://shop.acme.com",
                names("https://acme.com", "Acme main site", "https://shop.acme.com", "Shop"));
        assertThat(saved.getAllowedEmbedOrigins()).isEqualTo("https://acme.com,https://shop.acme.com");
        assertThat(EmbedSiteNames.fromJson(saved.getEmbedOriginNames()))
                .containsEntry("https://acme.com", "Acme main site");
    }

    @Test
    @DisplayName("removing a site drops its label too")
    void removingSiteDropsLabel() {
        patch("https://acme.com\nhttps://shop.acme.com", names("https://acme.com", "Acme", "https://shop.acme.com", "Shop"));
        FormDefinition saved = patch("https://acme.com", names("https://acme.com", "Acme", "https://shop.acme.com", "Shop"));
        assertThat(EmbedSiteNames.fromJson(saved.getEmbedOriginNames())).containsOnlyKeys("https://acme.com");
    }

    @Test
    @DisplayName("changing the allowlist alone RE-ANCHORS the existing labels")
    void reAnchorsWhenNamesOmitted() {
        // A client that only sends origins must not leave orphaned labels behind.
        patch("https://acme.com\nhttps://shop.acme.com", names("https://acme.com", "Acme", "https://shop.acme.com", "Shop"));
        FormDefinition saved = patch("https://acme.com", null);
        assertThat(EmbedSiteNames.fromJson(saved.getEmbedOriginNames())).containsOnlyKeys("https://acme.com");
    }

    @Test
    @DisplayName("clearing the allowlist clears every label")
    void clearingAllowlistClearsLabels() {
        patch("https://acme.com", names("https://acme.com", "Acme"));
        FormDefinition saved = patch("", null);
        assertThat(saved.getAllowedEmbedOrigins()).isNull();
        assertThat(saved.getEmbedOriginNames()).isNull();
    }

    @Test
    @DisplayName("labels can be edited without touching the allowlist")
    void labelsOnly() {
        patch("https://acme.com", names("https://acme.com", "Acme"));
        FormDefinition saved = controller.patchMeta("t1", "u1", "f1",
                new FormDefinitionController.MetaPatch(null, null, null, names("https://acme.com", "Renamed"), null));
        assertThat(saved.getAllowedEmbedOrigins()).isEqualTo("https://acme.com");
        assertThat(EmbedSiteNames.fromJson(saved.getEmbedOriginNames())).containsEntry("https://acme.com", "Renamed");
    }

    @Test
    @DisplayName("a label for an origin outside the allowlist is refused")
    void cannotLabelUnallowedOrigin() {
        FormDefinition saved = patch("https://acme.com", names("https://evil.test", "Sneaky"));
        assertThat(saved.getEmbedOriginNames()).isNull();
    }
}
