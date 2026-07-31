package com.luke.engine.document;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.access.CapabilityAccessService;
import com.luke.engine.capability.form.EmbedFormResolver;
import com.luke.engine.document.DocumentDtos.DocumentDto;
import com.luke.engine.document.DocumentDtos.FinalizeRequest;
import com.luke.engine.document.DocumentDtos.PublicAuthorizeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public embed document flow: a verified token's tenant scopes anonymous authorize → finalize → list →
 * delete → link, with the per-session attachment cap enforced. The token verification itself
 * (EmbedFormResolver) is mocked here — its security rules are covered by the embed-token tests.
 */
@DataJpaTest
@ExtendWith(MockitoExtension.class)
class EmbedDocumentServiceTest {

    @Autowired DocumentRepository repo;
    @Mock CapabilityAccessService capabilities;
    @Mock EmbedFormResolver resolver;

    private EmbedDocumentService embed;

    private static final String TOKEN = "good-token", REF = "embed-AB12", TENANT = "t1";

    /** This suite is about the upload flow, not billing — default to an entitled tenant. */
    private final com.luke.engine.branding.PlanFeatures plan = mock(com.luke.engine.branding.PlanFeatures.class);

    @BeforeEach
    void setUp() {
        DocumentAccessGuard guard = new DocumentAccessGuard(capabilities, new AllowAllTaskAccessResolver());
        DocumentService docs = new DocumentService(repo, guard, new DocumentRetentionPolicy(0, 2555),
                doc -> DocumentScanner.ScanVerdict.ok());
        embed = new EmbedDocumentService(docs, resolver, plan);
        when(resolver.resolveTenant(TOKEN)).thenReturn(TENANT);
        when(plan.canUseAttachments(anyString())).thenReturn(true);
    }

    @Test
    void authorizeFinalizeListDeleteLink() {
        PublicAuthorizeResponse auth = embed.authorize(TOKEN, REF, "id.pdf", "application/pdf");
        assertThat(auth.docId()).isNotBlank();
        assertThat(auth.tenantId()).isEqualTo(TENANT);
        assertThat(auth.storageKey()).startsWith(REF + "/").endsWith("-id.pdf");

        // anonymous → no createdBy; PENDING until finalize
        Document pending = repo.findByIdAndTenantId(auth.docId(), TENANT).orElseThrow();
        assertThat(pending.getCreatedBy()).isNull();
        assertThat(pending.getCapability()).isEqualTo("FORMS");
        assertThat(pending.getKind()).isEqualTo(Document.KIND_FORM_ATTACHMENT);

        DocumentDto ready = embed.finalizeUpload(TOKEN, auth.docId(), new FinalizeRequest(2048L, "abc123"));
        assertThat(ready.status()).isEqualTo(Document.STATUS_READY);

        assertThat(embed.list(TOKEN, REF)).hasSize(1);

        // bind to the instance created at submit
        assertThat(embed.link(TOKEN, REF, "inst-9")).isEqualTo(1);
        assertThat(repo.findByIdAndTenantId(auth.docId(), TENANT).orElseThrow().getOwnerEntityId()).isEqualTo("inst-9");

        // delete removes from the session list
        var drop = embed.delete(TOKEN, REF, auth.docId());
        assertThat(drop.tenantId()).isEqualTo(TENANT);
        assertThat(drop.storageKey()).isEqualTo(auth.storageKey());
        assertThat(drop.hardDelete()).isTrue();   // form attachments are never retained → erase immediately
        assertThat(embed.list(TOKEN, REF)).isEmpty();
    }

    @Test
    void enforcesPerSessionAttachmentCap() {
        for (int i = 0; i < 20; i++) {
            embed.authorize(TOKEN, REF, "f" + i + ".pdf", "application/pdf");
        }
        assertThatThrownBy(() -> embed.authorize(TOKEN, REF, "over.pdf", "application/pdf"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");
    }

    @Test
    void deleteRejectsWrongProcessRef() {
        PublicAuthorizeResponse auth = embed.authorize(TOKEN, REF, "id.pdf", "application/pdf");
        assertThatThrownBy(() -> embed.delete(TOKEN, "other-ref", auth.docId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
