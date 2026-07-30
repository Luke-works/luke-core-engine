package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The statement a form requires its filler to agree to before the submission counts — read from the
 * form's VERSIONED schema settings ({@code settings.consent = { enabled, text }}).
 *
 * <p><b>Why the schema and not the definition.</b> Enforceability rests on proving WHAT someone agreed
 * to, so the wording has to be pinned to the version they were shown. Living in the versioned schema
 * means changing it re-gates sign-off/publish (like any field edit) and an instance created against
 * v3 keeps v3's wording for ever, even after v4 rewords it.
 *
 * <p><b>Why the server reads it.</b> The browser is told the wording by the same schema it renders, but
 * the recorded text is resolved HERE, from the version the instance is pinned to — never taken from the
 * request. A tampered client can therefore change what it *displays*, but not what we *record*, so the
 * snapshot always says what the served form actually asked.
 *
 * <p><b>Fail-closed.</b> A form with consent switched on but no wording is a misconfiguration (the
 * builder blocks saving one). Rather than silently waive the requirement — the one failure mode that
 * would quietly cost a tenant their evidence — such a form still requires agreement, against
 * {@link #DEFAULT_TEXT}.
 */
public final class ConsentTerms {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Wording used when a form requires consent but its author left the statement blank. Deliberately
     * generic: it asserts only what any submission implies, so it is never *more* than the author asked.
     */
    public static final String DEFAULT_TEXT =
            "I confirm that the information I have provided is accurate, and I agree to it being "
            + "submitted and processed.";

    /** Longest statement we store. Beyond this someone is pasting a contract into a checkbox label. */
    public static final int MAX_LENGTH = 2000;

    private ConsentTerms() {}

    /**
     * The exact statement this schema demands agreement to, or {@code null} when the form does not
     * require consent. Tolerant of anything: an unparseable or absent schema means no requirement,
     * because a form whose contract we cannot read cannot be asserted to have asked for consent.
     */
    public static String requiredText(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return null;
        JsonNode consent;
        try {
            consent = MAPPER.readTree(schemaJson).path("settings").path("consent");
        } catch (Exception e) {
            return null;
        }
        if (!consent.path("enabled").asBoolean(false)) return null;
        String text = consent.path("text").asText("").trim();
        if (text.isEmpty()) return DEFAULT_TEXT; // switched on with no wording → still required
        return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
    }

    /** Whether this schema requires the filler to agree to anything. */
    public static boolean isRequired(String schemaJson) {
        return requiredText(schemaJson) != null;
    }
}
