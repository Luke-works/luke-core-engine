package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stateless helpers for form codes, instance tokens, and deriving the
 * field→variable contract from a stored coltorapps schema.
 */
public final class FormSupport {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FormSupport() {}

    /** Human form code, e.g. "FM-XKQW-05JUN26" (all caps, current date). */
    public static String generateCode() {
        return generateCode(LocalDate.now());
    }

    public static String generateCode(LocalDate date) {
        StringBuilder letters = new StringBuilder(4);
        for (int i = 0; i < 4; i++) letters.append(LETTERS.charAt(RNG.nextInt(26)));
        String month = date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH);
        String dd = String.format("%02d", date.getDayOfMonth());
        String yy = String.format("%02d", date.getYear() % 100);
        return "FM-" + letters + "-" + dd + month + yy;
    }

    /** Opaque, URL-safe instance token, e.g. "inv_xxxxxxxxxxxx". */
    public static String generateToken() {
        StringBuilder sb = new StringBuilder("inv_");
        for (int i = 0; i < 12; i++) {
            int n = RNG.nextInt(36);
            sb.append(n < 10 ? (char) ('0' + n) : (char) ('a' + n - 10));
        }
        return sb.toString();
    }

    /**
     * Flatten a coltorapps schema string into the field→variable contract:
     * {@code [{key, type, required, conditional}]}. Best-effort — any entity carrying a
     * {@code key} attribute is treated as an input field. Returns an empty list
     * for blank/unparseable schemas rather than throwing.
     *
     * <p>{@code conditional} is true when the server can't safely hard-enforce {@code required}:
     * the field is statically hidden or disabled, has a literal/custom conditional, a computed
     * value, show/hide/require/optional/enable/disable logic, OR is {@code persistent:false} (the
     * renderer omits non-persistent fields from the submission entirely). Callers that enforce
     * {@code required} (e.g. the public-submit backstop) must NOT hard-enforce it for such fields:
     * the value may legitimately never arrive, so its absence is not an error — the client renderer
     * enforces required only when the field is actually shown and submitted.
     */
    public static List<Map<String, Object>> extractFields(String schema) {
        List<Map<String, Object>> fields = new ArrayList<>();
        if (schema == null || schema.isBlank()) return fields;
        try {
            JsonNode entities = MAPPER.readTree(schema).path("entities");
            entities.fields().forEachRemaining(entry -> {
                JsonNode entity = entry.getValue();
                JsonNode attrs = entity.path("attributes");
                JsonNode keyNode = attrs.path("key");
                if (keyNode.isMissingNode() || keyNode.asText("").isBlank()) return; // not an input field
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("key", keyNode.asText());
                field.put("type", mapType(entity.path("type").asText("")));
                field.put("required", attrs.path("required").asBoolean(false));
                field.put("conditional", isConditionallyControlled(attrs));
                fields.add(field);
            });
        } catch (Exception ignored) {
            // best-effort: a malformed draft just yields no contract
        }
        return fields;
    }

    /** Field actions (in {@code logic[]}) that make a field's visibility / editability / required
     *  state dynamic, so the server can't know whether it was shown / must be filled. */
    private static final Set<String> DYNAMIC_ACTIONS =
            Set.of("show", "hide", "require", "optional", "enable", "disable", "setValue");

    /** Whether the field's presence/requiredness is conditional (see {@link #extractFields}). */
    private static boolean isConditionallyControlled(JsonNode attrs) {
        if (attrs.path("hidden").asBoolean(false)) return true;   // statically hidden — never shown
        if (attrs.path("disabled").asBoolean(false)) return true; // statically disabled — user can't fill
        // persistent:false → the renderer deliberately omits this field from the submission payload
        // (form-core collect() skips it), so it can NEVER arrive — hard-requiring it always 400s.
        if (!attrs.path("persistent").asBoolean(true)) return true;
        if (!attrs.path("customConditional").asText("").isBlank()) return true; // visibility expression
        if (!attrs.path("calculateValue").asText("").isBlank()) return true;    // computed/derived value
        JsonNode cond = attrs.path("conditional");
        if (cond.isObject() && cond.size() > 0) return true;      // literal conditional visibility
        JsonNode logic = attrs.path("logic");
        if (logic.isArray()) {
            for (JsonNode rule : logic) {
                if (DYNAMIC_ACTIONS.contains(rule.path("action").asText(""))) return true;
            }
        }
        return false;
    }

    /** Map a builder field type to the engine variable type used at runtime. */
    private static String mapType(String fieldType) {
        return switch (fieldType) {
            case "number" -> "Long";
            case "currency" -> "Double";
            case "checkbox", "toggle" -> "Boolean";
            case "date", "time", "day" -> "Date";
            case "selectBoxes", "tags", "table", "signature", "file" -> "Json";
            default -> "String";
        };
    }
}
