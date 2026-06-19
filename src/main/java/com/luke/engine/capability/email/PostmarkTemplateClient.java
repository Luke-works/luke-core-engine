package com.luke.engine.capability.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import com.luke.engine.config.RestTemplates;
import java.time.Duration;

/**
 * Postmark <em>Templates</em> API client — upserts a stored template (the HTML/text
 * the UI compiled in the browser) so that template sends can reference it by alias.
 * Uses the per-server send token in {@code X-Postmark-Server-Token}, like
 * {@link PostmarkClient}. The token is passed per call and never logged.
 *
 * <p>An upsert creates the template via {@code POST /templates}; if its alias already
 * exists Postmark answers with a duplicate-alias error, in which case we fall back to
 * {@code PUT /templates/{alias}} so re-publishing updates the same Postmark template.
 *
 * <p>Unlike a transactional send, a publish is an explicit authoring action: a
 * Postmark failure throws so the caller can surface a clear error and not record a
 * bogus alias.
 */
@Component
public class PostmarkTemplateClient {

    private static final Logger log = LoggerFactory.getLogger(PostmarkTemplateClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Postmark ErrorCode for "a template with this alias already exists" (POST). */
    private static final int ERR_TEMPLATE_ALIAS_EXISTS = 1105;

    private final RestTemplate rest = RestTemplates.withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(15));

    @Value("${luke.email.postmark.base-url:https://api.postmarkapp.com}")
    private String baseUrl;

    /** Result of an upsert: the Postmark template id and the alias it lives under. */
    public record UpsertResult(Long templateId, String alias) {}

    /**
     * Create (or update, if the alias already exists) a stored Postmark template under
     * {@code alias} with the given compiled bodies. Returns the Postmark template id +
     * alias. Throws on a Postmark/HTTP error (no usable response).
     */
    public UpsertResult upsert(String serverToken, String alias, String name,
                               String subject, String htmlBody, String textBody) {
        if (serverToken == null || serverToken.isBlank()) {
            throw new IllegalStateException("No Postmark server token available for this publish");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "Name", name);
        put(body, "Alias", alias);
        put(body, "Subject", subject);
        put(body, "HtmlBody", htmlBody);
        put(body, "TextBody", textBody);
        body.put("TemplateType", "Standard");

        try {
            JsonNode resp = exchange(HttpMethod.POST, "/templates", serverToken, body);
            return parse(resp, alias);
        } catch (HttpStatusCodeException e) {
            if (aliasAlreadyExists(e)) {
                // Update the existing template under the same alias (Alias is immutable on PUT).
                body.remove("Alias");
                JsonNode resp = exchange(HttpMethod.PUT, "/templates/" + alias, serverToken, body);
                return parse(resp, alias);
            }
            throw asError(e);
        }
    }

    /* ── helpers ────────────────────────────────────────────── */

    private JsonNode exchange(HttpMethod method, String path, String serverToken, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Postmark-Server-Token", serverToken);
        return rest.exchange(baseUrl + path, method, new HttpEntity<>(body, headers), JsonNode.class).getBody();
    }

    private static UpsertResult parse(JsonNode resp, String alias) {
        Long templateId = resp != null && resp.hasNonNull("TemplateId") ? resp.get("TemplateId").asLong() : null;
        String resolvedAlias = resp != null && resp.hasNonNull("Alias") ? resp.get("Alias").asText() : alias;
        if (templateId == null) {
            throw new IllegalStateException("Postmark returned no TemplateId for the published template");
        }
        return new UpsertResult(templateId, resolvedAlias);
    }

    /** Whether a POST /templates failure was Postmark's "alias already exists". */
    private static boolean aliasAlreadyExists(HttpStatusCodeException e) {
        Integer code = errorCode(e.getResponseBodyAsString());
        return code != null && code == ERR_TEMPLATE_ALIAS_EXISTS;
    }

    private static IllegalStateException asError(HttpStatusCodeException e) {
        String message = messageFrom(e.getResponseBodyAsString(),
                "HTTP " + e.getStatusCode().value() + " — " + e.getStatusText());
        log.warn("Postmark template upsert failed: {}", message);
        return new IllegalStateException("Postmark could not save the template: " + message);
    }

    private static Integer errorCode(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node.hasNonNull("ErrorCode")) return node.get("ErrorCode").asInt();
        } catch (Exception ignored) {
            // not JSON
        }
        return null;
    }

    /** Pull Postmark's human "Message" out of an error body, else the fallback. */
    private static String messageFrom(String body, String fallback) {
        if (body == null || body.isBlank()) return fallback;
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node.hasNonNull("Message")) return node.get("Message").asText();
        } catch (Exception ignored) {
            // not JSON
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    /** Drop null/blank fields Postmark would reject. */
    private static void put(Map<String, Object> body, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        body.put(key, value);
    }
}
