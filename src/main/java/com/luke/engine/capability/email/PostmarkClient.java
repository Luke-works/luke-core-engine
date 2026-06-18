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

    private final RestTemplate rest = new RestTemplate();

    @Value("${luke.email.postmark.base-url:https://api.postmarkapp.com}")
    private String baseUrl;

    /** Outcome of a Postmark submission. ok=true → SENT with a messageId. */
    public record SendResult(boolean ok, String messageId, Integer errorCode, String message) {}

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

    private SendResult submit(String path, String serverToken, Map<String, Object> fields) {
        if (serverToken == null || serverToken.isBlank()) {
            return new SendResult(false, null, null, "No Postmark server token available for this send");
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
                return new SendResult(true, messageId.toString(), 0, message);
            }
            return new SendResult(false, messageId != null ? messageId.toString() : null,
                    errorCode, message != null ? message : "Postmark did not confirm delivery");
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
            return new SendResult(false, null, errorCode, message);
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Postmark send errored: {}", detail);
            return new SendResult(false, null, null, detail);
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
