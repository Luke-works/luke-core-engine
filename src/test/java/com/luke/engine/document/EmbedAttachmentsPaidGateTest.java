package com.luke.engine.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.branding.PlanFeatures;
import com.luke.engine.capability.form.EmbedFormResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * File attachments are a PAID feature, and the public embed upload endpoint is the boundary that
 * matters: the render payload hides the tab for a free tenant, but that is a courtesy to the filler —
 * this endpoint is reachable by anyone holding the embed token.
 */
class EmbedAttachmentsPaidGateTest {

    private static final String TOKEN = "tok", REF = "embed-AB12", TENANT = "t1";

    private final DocumentService documents = mock(DocumentService.class);
    private final EmbedFormResolver resolver = mock(EmbedFormResolver.class);
    private final PlanFeatures plan = mock(PlanFeatures.class);
    private EmbedDocumentService embed;

    @BeforeEach
    void setUp() {
        embed = new EmbedDocumentService(documents, resolver, plan);
        when(resolver.resolveTenant(TOKEN)).thenReturn(TENANT);
    }

    @Test
    @DisplayName("a free tenant's upload is refused with 402, and never reaches storage")
    void freeIsRefused() {
        when(plan.canUseAttachments(TENANT)).thenReturn(false);

        assertThatThrownBy(() -> embed.authorize(TOKEN, REF, "cv.pdf", "application/pdf"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.PAYMENT_REQUIRED));

        verify(documents, never()).authorizeAnonymous(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the refusal happens BEFORE any counting, so blocked uploads cost nothing")
    void refusedBeforeCounting() {
        // Otherwise a free tenant's rejected attempts would still burn the per-token rate-limit
        // window and the attachment-count query on every try.
        when(plan.canUseAttachments(TENANT)).thenReturn(false);

        assertThatThrownBy(() -> embed.authorize(TOKEN, REF, "cv.pdf", "application/pdf"))
                .isInstanceOf(ResponseStatusException.class);

        verify(documents, never()).countActiveAnonymous(anyString(), anyString());
    }

    @Test
    @DisplayName("a paid tenant's upload proceeds")
    void paidProceeds() {
        when(plan.canUseAttachments(TENANT)).thenReturn(true);
        when(documents.countActiveAnonymous(TENANT, REF)).thenReturn(0L);
        when(documents.authorizeAnonymous(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DocumentDtos.AuthorizeResponse("d1", "key/1", 0L, null));

        var out = embed.authorize(TOKEN, REF, "cv.pdf", "application/pdf");

        assertThat(out.docId()).isEqualTo("d1");
        assertThat(out.tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("entitlement is checked against the TOKEN's tenant, never a caller-supplied one")
    void entitlementFollowsTheToken() {
        // The token is the only unforgeable thing here; asking the plan about anything else would let
        // a caller borrow a paying tenant's entitlement.
        when(plan.canUseAttachments(TENANT)).thenReturn(false);
        assertThatThrownBy(() -> embed.authorize(TOKEN, REF, "cv.pdf", "application/pdf"))
                .isInstanceOf(ResponseStatusException.class);
        verify(plan).canUseAttachments(TENANT);
    }
}
