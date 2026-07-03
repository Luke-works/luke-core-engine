package com.luke.engine.capability.form;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Single home for the embed-token → tenant/form security check (verify signature, form exists +
 * published, embed key not rotated). Shared by {@link FormEmbedController} (public render/submit) and
 * the public document-upload flow ({@code EmbedDocumentService}), so the unforgeable-tenant +
 * revocation rules can never diverge between the two unauthenticated surfaces.
 */
@Component
public class EmbedFormResolver {

    private final EmbedTokens embedTokens;
    private final FormDefinitionRepository forms;

    public EmbedFormResolver(EmbedTokens embedTokens, FormDefinitionRepository forms) {
        this.embedTokens = embedTokens;
        this.forms = forms;
    }

    /** A verified token's tenant + its published form. */
    public record Resolved(EmbedTokens.EmbedRef ref, FormDefinition form) {
        public String tenantId() {
            return ref.tenantId();
        }
    }

    /** Verify the token and resolve its published, non-revoked form, or 404 (never leak token validity). */
    public Resolved resolve(String token) {
        EmbedTokens.EmbedRef ref;
        try {
            ref = embedTokens.verify(token);
        } catch (IllegalArgumentException e) {
            throw notFound("Unknown or invalid form link.");
        }
        FormDefinition form = forms.findByTenantIdAndCode(ref.tenantId(), ref.code())
                .filter(f -> f.getDeletedAt() == null)
                .orElseThrow(() -> notFound("This form is no longer available."));
        if (form.getPublishedVersion() == null) {
            throw notFound("This form is not published.");
        }
        // Kind gating (mirrors FormDefinitionController#requireEmbeddable): outbound forms are sent to a
        // recipient, never embedded; an inbound form is embeddable only once its submission handling is
        // decided. Same 404 surface — never leak why to the public embed endpoint.
        if (FormDefinition.KIND_OUTBOUND.equals(form.getKind())) {
            throw notFound("This form is not available.");
        }
        if (form.getSubmissionHandling() == null || form.getSubmissionHandling().isBlank()) {
            throw notFound("This form is not accepting submissions yet.");
        }
        // Revocation: a token minted before the form's embed key was rotated is dead.
        if (ref.keyVersion() != form.getEmbedKeyVersion()) {
            throw notFound("Unknown or invalid form link.");
        }
        return new Resolved(ref, form);
    }

    /** Convenience: just the unforgeable tenant id for a valid token. */
    public String resolveTenant(String token) {
        return resolve(token).tenantId();
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
