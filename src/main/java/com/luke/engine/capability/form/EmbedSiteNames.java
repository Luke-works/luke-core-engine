package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Friendly LABELS for the origins in a form's embed allowlist — "Acme main site" beside
 * {@code https://acme.com} — so an author managing several sites can tell them apart.
 *
 * <p><b>Deliberately separate from the allowlist itself.</b> {@code allowedEmbedOrigins} is the input
 * to {@link FrameAncestors#directive}, i.e. the CSP that decides who may frame the form; its format
 * is a security surface. Labels are decoration. Keeping them in their own column means naming a site
 * can never change, widen or corrupt the policy, and a label that drifts out of sync with the
 * allowlist is a cosmetic nuisance rather than a hole.
 *
 * <p>Stored as a JSON object keyed by the CANONICAL origin (lower-cased, as
 * {@link FrameAncestors#normalizeList} emits it), so a label always lines up with the entry the
 * browser is actually enforcing.
 */
public final class EmbedSiteNames {

    private EmbedSiteNames() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Longest label we keep. Long enough for "Acme Corporation — checkout pages", short enough that
     *  the column can't be used as free storage. Longer input is truncated, not rejected: a label is
     *  not worth failing a save over. */
    static final int MAX_NAME = 80;

    /**
     * Serialize {@code names} to JSON, keeping only labels whose origin is in {@code allowedCsv}.
     *
     * <p>Anchoring to the allowlist is what stops this column growing without bound: an origin the
     * author removed takes its label with it, and a client cannot persist labels for origins that
     * were never allowed. Returns {@code null} when nothing survives, so "no labels" is one value
     * rather than an empty object.
     */
    public static String toJson(Map<String, String> names, String allowedCsv) {
        String normalized = FrameAncestors.normalizeList(allowedCsv);
        if (names == null || names.isEmpty() || normalized == null) return null;
        Set<String> allowed = Set.of(normalized.split(","));
        ObjectNode out = MAPPER.createObjectNode();
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String origin = e.getKey().trim().toLowerCase();
            if (origin.endsWith("/")) origin = origin.substring(0, origin.length() - 1);
            if (!allowed.contains(origin)) continue;
            String label = e.getValue().trim();
            if (label.isEmpty()) continue; // clearing a label removes it, rather than storing ""
            if (label.length() > MAX_NAME) label = label.substring(0, MAX_NAME);
            out.put(origin, label);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /**
     * Read the stored JSON back into a map. Never throws: this column is decoration, and a form whose
     * labels somehow became unparseable must still open, embed and serve — just unlabelled.
     */
    public static Map<String, String> fromJson(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) return out;
            node.fields().forEachRemaining(e -> {
                if (e.getValue() != null && e.getValue().isTextual()) out.put(e.getKey(), e.getValue().asText());
            });
        } catch (Exception ignored) {
            // Unreadable labels are not an error worth surfacing — the allowlist is unaffected.
        }
        return out;
    }
}
