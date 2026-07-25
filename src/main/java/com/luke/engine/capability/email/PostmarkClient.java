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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import com.luke.engine.config.RestTemplates;
import java.time.Duration;

/**
 * Thin Postmark transactional-email client. Posts to the Postmark Email API with
 * the server token in {@code X-Postmark-Server-Token} and translates the response
 * into a {@link SendResult}. Two entry points — a raw HTML/text send and a
 * stored-template send — both share the same submit + parse path.
 *
 * <p>Never throws on a delivery failure: Postmark's ErrorCode/Message (or an HTTP
 * error) is captured in the result so the caller can record it on the audit row.
 * The server token is passed per call — each tenant sends with its own Postmark
 * Server token (see {@link EmailServerService}).
 */
@Component
public class PostmarkClient {

    private static final Logger log = LoggerFactory.getLogger(PostmarkClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest = RestTemplates.withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(15));

    @Value("${luke.email.postmark.base-url:https://api.postmarkapp.com}")
    private String baseUrl;

    /** Outcome of a Postmark submission. ok=true → SENT with a messageId. {@code retryable} marks a
     *  TRANSIENT failure (network error / Postmark 5xx) that is worth retrying; a business rejection
     *  (bad recipient, 4xx) or a config problem is NOT retryable — retrying it just fails again and,
     *  for anything Postmark already accepted, risks a double-send. */
    public record SendResult(boolean ok, String messageId, Integer errorCode, String message, boolean retryable) {}

    /**
     * Send a raw email with the given Postmark Server token. {@code fields} carries
     * the Postmark fields already in PascalCase form built by the service
     * (From/To/Subject/HtmlBody/…). Posts to {@code /email}.
     */
    public SendResult send(String serverToken, Map<String, Object> fields) {
        return submit("/email", serverToken, fields);
    }

    /**
     * Send a stored-template email with the given Postmark Server token.
     * {@code fields} carries From/To plus TemplateId|TemplateAlias and
     * TemplateModel. Posts to {@code /email/withTemplate}.
     */
    public SendResult sendTemplate(String serverToken, Map<String, Object> fields) {
        return submit("/email/withTemplate", serverToken, fields);
    }

    /** Outcome of creating a Postmark message stream. */
    public record StreamResult(boolean ok, String streamId, String error) {}

    /**
     * Create a Postmark <b>message stream</b> on the server owning {@code serverToken}
     * (per-stream stats/reputation). {@code type} is {@code Transactional} | {@code Broadcasts}
     * | {@code Inbound}. The stream {@code id} must be lowercase alphanumeric + hyphens and
     * unique within the server; a 422 "already exists" is treated as success (idempotent).
     * Uses the Message Streams API ({@code POST /message-streams}).
     */
    public StreamResult createMessageStream(String serverToken, String id, String name, String type) {
        if (serverToken == null || serverToken.isBlank()) {
            return new StreamResult(false, null, "No Postmark server token available");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            headers.set("X-Postmark-Server-Token", serverToken);

            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("ID", id);
            reqBody.put("Name", name);
            reqBody.put("MessageStreamType", type);

            JsonNode resp = rest.postForObject(
                    baseUrl + "/message-streams", new HttpEntity<>(reqBody, headers), JsonNode.class);
            String created = resp != null && resp.hasNonNull("ID") ? resp.get("ID").asText() : id;
            return new StreamResult(true, created, null);
        } catch (HttpStatusCodeException e) {
            String body = e.getResponseBodyAsString();
            // ErrorCode 1211 = "A message stream with this ID already exists" → idempotent success.
            try {
                JsonNode node = MAPPER.readTree(body);
                int code = node.hasNonNull("ErrorCode") ? node.get("ErrorCode").asInt() : -1;
                String msg = node.hasNonNull("Message") ? node.get("Message").asText() : body;
                if (code == 1211 || (msg != null && msg.toLowerCase().contains("already exists"))) {
                    return new StreamResult(true, id, null);
                }
                log.warn("Postmark create-stream failed: {} (code {})", msg, code);
                return new StreamResult(false, null, msg);
            } catch (Exception ignored) {
                return new StreamResult(false, null, "HTTP " + e.getStatusCode().value());
            }
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Postmark create-stream errored: {}", detail);
            return new StreamResult(false, null, detail);
        }
    }

    private SendResult submit(String path, String serverToken, Map<String, Object> fields) {
        if (serverToken == null || serverToken.isBlank()) {
            return new SendResult(false, null, null, "No Postmark server token available for this send", false);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            headers.set("X-Postmark-Server-Token", serverToken);

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.postForObject(
                    baseUrl + path, new HttpEntity<>(fields, headers), Map.class);

            // Postmark returns ErrorCode 0 + a MessageID on success.
            Integer errorCode = asInt(resp != null ? resp.get("ErrorCode") : null);
            Object messageId = resp != null ? resp.get("MessageID") : null;
            String message = resp != null && resp.get("Message") != null ? resp.get("Message").toString() : null;
            if (errorCode != null && errorCode == 0 && messageId != null) {
                return new SendResult(true, messageId.toString(), 0, message, false);
            }
            // A 200 with a non-zero ErrorCode is a Postmark business rejection — retrying won't help.
            return new SendResult(false, messageId != null ? messageId.toString() : null,
                    errorCode, message != null ? message : "Postmark did not confirm delivery", false);
        } catch (HttpStatusCodeException e) {
            // A 422 carries Postmark's ErrorCode/Message JSON; other statuses may not.
            String body = e.getResponseBodyAsString();
            Integer errorCode = null;
            String message = null;
            try {
                JsonNode node = MAPPER.readTree(body);
                if (node.hasNonNull("ErrorCode")) errorCode = node.get("ErrorCode").asInt();
                if (node.hasNonNull("Message")) message = node.get("Message").asText();
            } catch (Exception ignored) {
                // not JSON — fall through to the HTTP status
            }
            if (message == null) message = "HTTP " + e.getStatusCode().value() + " — " + e.getStatusText();
            log.warn("Postmark send failed: {} (errorCode {})", message, errorCode);
            // 5xx = Postmark server error → the message wasn't accepted, safe to retry; 4xx = client
            // error (bad recipient/payload) → permanent.
            return new SendResult(false, null, errorCode, message, e.getStatusCode().is5xxServerError());
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Postmark send errored: {}", detail);
            // Connection/timeout/DNS — no response from Postmark. Transient: retry.
            return new SendResult(false, null, null, detail, true);
        }
    }

    /** Build the Postmark wire body, dropping null/blank fields Postmark would reject. */
    static Map<String, Object> body() {
        return new LinkedHashMap<>();
    }

    static void put(Map<String, Object> body, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        if (value instanceof Map<?, ?> m && m.isEmpty()) return;
        body.put(key, value);
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
