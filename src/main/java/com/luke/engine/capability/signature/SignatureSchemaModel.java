package com.luke.engine.capability.signature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view over the design-time SignatureSchema JSON (which the engine otherwise treats as
 * opaque). At campaign-start / signing we need the document key, the signer ROLES (to bind
 * recipients), the declared VARIABLES (the {{key}} data attributes the campaign must supply), and
 * the placed FIELDS (to render each recipient's portion). Tolerant of shape drift.
 */
public final class SignatureSchemaModel {

    private SignatureSchemaModel() {}

    public record Signer(String id, String label, int order, String verify) {}
    public record Variable(String key, String label, boolean required) {}
    public record Field(String id, String signerId, String type, int page, double x, double y, double w, double h) {}
    public record Parsed(String documentKey, String documentName, String routing,
                         List<Signer> signers, List<Variable> variables, List<Field> fields) {}

    public static Parsed parse(ObjectMapper mapper, String schemaJson) {
        List<Signer> signers = new ArrayList<>();
        List<Variable> variables = new ArrayList<>();
        List<Field> fields = new ArrayList<>();
        String documentKey = null;
        String documentName = null;
        String routing = "sequential";
        if (schemaJson == null || schemaJson.isBlank()) {
            return new Parsed(null, null, routing, signers, variables, fields);
        }
        try {
            JsonNode root = mapper.readTree(schemaJson);
            JsonNode doc = root.path("document");
            documentKey = doc.path("key").asText(null);
            documentName = doc.path("name").asText(null);
            if (root.hasNonNull("routing")) routing = root.path("routing").asText("sequential");

            JsonNode s = root.path("signers");
            if (s.isArray()) {
                int i = 0;
                for (JsonNode n : s) {
                    i++;
                    String id = n.path("id").asText(null);
                    if (id == null || id.isBlank()) continue;
                    signers.add(new Signer(id, n.path("label").asText("Signer " + i),
                            n.path("order").asInt(i), n.path("verify").asText("NONE")));
                }
            }
            JsonNode v = root.path("variables");
            if (v.isArray()) {
                for (JsonNode n : v) {
                    String key = n.path("key").asText(null);
                    if (key == null || key.isBlank()) continue;
                    variables.add(new Variable(key, n.path("label").asText(key), n.path("required").asBoolean(false)));
                }
            }
            JsonNode f = root.path("fields");
            if (f.isArray()) {
                for (JsonNode n : f) {
                    String id = n.path("id").asText(null);
                    if (id == null || id.isBlank()) continue;
                    fields.add(new Field(id, n.path("signerId").asText(null), n.path("type").asText("SIGNATURE"),
                            n.path("page").asInt(0), n.path("x").asDouble(0), n.path("y").asDouble(0),
                            n.path("w").asDouble(0), n.path("h").asDouble(0)));
                }
            }
        } catch (Exception ignored) {
            // malformed schema → whatever parsed; campaign-start validation surfaces real problems
        }
        return new Parsed(documentKey, documentName, routing, signers, variables, fields);
    }
}
