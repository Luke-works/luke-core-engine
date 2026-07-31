package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Server-side reads of a form's VERSIONED schema settings ({@code settings.*}).
 *
 * <p>The schema is authored and stored as opaque JSON, but a few settings have consequences the
 * engine must enforce rather than trust the browser about. This is the one place that reaches into
 * it for those, so "what the schema says" and "what the server does" cannot drift across callers.
 * {@link ConsentTerms} owns the consent settings for the same reason.
 *
 * <p>Every read is tolerant: unparseable or absent settings fall back to the safe default rather
 * than throwing. A form whose schema JSON is somehow damaged must still render.
 */
public final class FormSettingsRead {

    private FormSettingsRead() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Does this form's schema ask for file attachments? Mirrors the client's
     * {@code readAttachmentsEnabled} — {@code settings.attachments === true}.
     *
     * <p>This is the AUTHOR's request, not the answer: attachments are a paid feature, so a caller
     * must still gate it through {@link com.luke.engine.branding.PlanFeatures#canUseAttachments}.
     * Kept separate so the two questions — "did they ask for it" and "are they entitled to it" —
     * can never be conflated into one flag that means neither.
     */
    public static boolean attachmentsEnabled(String schemaJson) {
        return settings(schemaJson).path("attachments").asBoolean(false);
    }

    private static JsonNode settings(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return MAPPER.createObjectNode();
        try {
            return MAPPER.readTree(schemaJson).path("settings");
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
