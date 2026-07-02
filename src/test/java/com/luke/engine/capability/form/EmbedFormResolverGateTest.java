package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** The embed gate: only a published INBOUND form with decided submission handling resolves;
 *  outbound and undecided-inbound forms 404 (never leaking the reason to the public surface). */
class EmbedFormResolverGateTest {

    private final EmbedTokens tokens = mock(EmbedTokens.class);
    private final FormDefinitionRepository forms = mock(FormDefinitionRepository.class);
    private final EmbedFormResolver resolver = new EmbedFormResolver(tokens, forms);

    private FormDefinition form(String kind, String handling, int keyVersion) {
        FormDefinition f = new FormDefinition();
        f.setTenantId("t1");
        f.setCode("FM-1");
        f.setKind(kind);
        f.setSubmissionHandling(handling);
        f.setPublishedVersion(1);
        f.setEmbedKeyVersion(keyVersion);
        return f;
    }

    private void stub(FormDefinition f) {
        when(tokens.verify("tok")).thenReturn(new EmbedTokens.EmbedRef("t1", "FM-1", f.getEmbedKeyVersion()));
        when(forms.findByTenantIdAndCode("t1", "FM-1")).thenReturn(Optional.of(f));
    }

    @Test
    void inboundWithSubmissionHandlingResolves() {
        stub(form(FormDefinition.KIND_INBOUND, "COLLECT", 0));
        EmbedFormResolver.Resolved r = resolver.resolve("tok");
        assertThat(r.tenantId()).isEqualTo("t1");
        assertThat(r.form().getCode()).isEqualTo("FM-1");
    }

    @Test
    void inboundWithoutSubmissionHandlingIsBlocked() {
        stub(form(FormDefinition.KIND_INBOUND, null, 0));
        assertThatThrownBy(() -> resolver.resolve("tok"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not accepting submissions");
    }

    @Test
    void outboundIsNeverEmbeddable() {
        stub(form(FormDefinition.KIND_OUTBOUND, "COLLECT", 0));
        assertThatThrownBy(() -> resolver.resolve("tok"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not available");
    }
}
