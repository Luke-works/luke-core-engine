package com.luke.engine.capability.phone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.config.RestTemplates;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Thin Vapi (https://vapi.ai) REST client. Authenticates with the tenant's <em>private</em>
 * API key as a Bearer token and translates responses into result records — mirroring
 * {@link com.luke.engine.capability.email.PostmarkClient}. The key is passed per call so
 * each tenant uses its own (see {@link VapiCredentials}).
 *
 * <p>Never throws on a provider failure: Vapi's error body (or an HTTP error) is captured
 * in the result so the caller can record it on the audit row instead of failing the request.
 * Used for placing outbound calls and managing phone numbers; inbound calls and call
 * lifecycle arrive over the webhook (see {@link PublicPhoneWebhookController}).
 */
@Component
public class VapiClient {

    private static final Logger log = LoggerFactory.getLogger(VapiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest = RestTemplates.withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(15));

    @Value("${luke.phone.vapi.base-url:https://api.vapi.ai}")
    private String baseUrl;

    /* ── outbound calls ─────────────────────────────────────────── */

    /** Outcome of placing a call. ok=true → Vapi accepted it and returned a call id. */
    public record CallResult(boolean ok, String callId, String status, String error) {}

    /**
     * Place an outbound phone call. {@code variableValues} populate {@code {{template}}}
     * variables in the assistant's prompts/first message; {@code metadata} rides along on
     * the Call object and every webhook for this call. Either {@code assistantId} or an
     * inline assistant is required by Vapi — we pass the saved {@code assistantId}.
     */
    public CallResult createCall(String apiKey, String phoneNumberId, String customerNumber,
                                 String assistantId, Map<String, Object> variableValues,
                                 Map<String, Object> metadata) {
        if (isBlank(apiKey)) return new CallResult(false, null, null, "No Vapi API key configured for this tenant");
        if (isBlank(phoneNumberId)) return new CallResult(false, null, null, "No Vapi phoneNumberId to call from");
        if (isBlank(customerNumber)) return new CallResult(false, null, null, "customer number is required");
        if (isBlank(assistantId)) return new CallResult(false, null, null, "No Vapi assistantId to place the call");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phoneNumberId", phoneNumberId);
        body.put("customer", Map.of("number", customerNumber));
        body.put("assistantId", assistantId);
        if (variableValues != null && !variableValues.isEmpty()) {
            body.put("assistantOverrides", Map.of("variableValues", variableValues));
        }
        if (metadata != null && !metadata.isEmpty()) {
            body.put("metadata", metadata);
        }
        try {
            JsonNode resp = post(apiKey, "/call", body);
            String id = text(resp, "id");
            String status = text(resp, "status");
            if (id != null) return new CallResult(true, id, status, null);
            return new CallResult(false, null, status, "Vapi did not return a call id");
        } catch (HttpStatusCodeException e) {
            String msg = errorMessage(e);
            log.warn("Vapi createCall failed: {}", msg);
            return new CallResult(false, null, null, msg);
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Vapi createCall errored: {}", detail);
            return new CallResult(false, null, null, detail);
        }
    }

    /* ── phone numbers ──────────────────────────────────────────── */

    /** Outcome of provisioning/importing a number. ok=true → Vapi returned a number id. */
    public record NumberResult(boolean ok, String numberId, String number, String provider, String error) {}

    /**
     * Provision a Vapi-provided number (US-only, capped per account). {@code areaCode}
     * is optional; {@code assistantId} attaches the answering assistant.
     */
    public NumberResult buyVapiNumber(String apiKey, String areaCode, String name, String assistantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", "vapi");
        put(body, "numberDesiredAreaCode", areaCode);
        put(body, "name", name);
        put(body, "assistantId", assistantId);
        return createNumber(apiKey, body, "vapi");
    }

    /**
     * Import a BYO carrier number (twilio/telnyx/vonage) already configured in Vapi via a
     * stored credential. The provider credentials live in Vapi referenced by
     * {@code credentialId}; we never handle the carrier secret here.
     */
    public NumberResult importNumber(String apiKey, String provider, String number,
                                     String credentialId, String name, String assistantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "provider", provider);
        put(body, "number", number);
        put(body, "credentialId", credentialId);
        put(body, "name", name);
        put(body, "assistantId", assistantId);
        return createNumber(apiKey, body, provider);
    }

    private NumberResult createNumber(String apiKey, Map<String, Object> body, String provider) {
        if (isBlank(apiKey)) return new NumberResult(false, null, null, provider, "No Vapi API key configured for this tenant");
        try {
            JsonNode resp = post(apiKey, "/phone-number", body);
            String id = text(resp, "id");
            String number = text(resp, "number");
            if (id != null) return new NumberResult(true, id, number, text(resp, "provider"), null);
            return new NumberResult(false, null, null, provider, "Vapi did not return a phone-number id");
        } catch (HttpStatusCodeException e) {
            String msg = errorMessage(e);
            log.warn("Vapi createNumber failed: {}", msg);
            return new NumberResult(false, null, null, provider, msg);
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Vapi createNumber errored: {}", detail);
            return new NumberResult(false, null, null, provider, detail);
        }
    }

    /** A phone number as Vapi lists it. */
    public record ListedNumber(String id, String number, String provider, String name, String assistantId) {}

    /** List the numbers on the Vapi account for {@code apiKey}; empty on any error. */
    public List<ListedNumber> listNumbers(String apiKey) {
        if (isBlank(apiKey)) return List.of();
        try {
            JsonNode resp = get(apiKey, "/phone-number");
            List<ListedNumber> out = new ArrayList<>();
            if (resp != null && resp.isArray()) {
                for (JsonNode n : resp) {
                    out.add(new ListedNumber(text(n, "id"), text(n, "number"),
                            text(n, "provider"), text(n, "name"), text(n, "assistantId")));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Vapi listNumbers errored: {}", e.getMessage());
            return List.of();
        }
    }

    /* ── HTTP plumbing ──────────────────────────────────────────── */

    private JsonNode post(String apiKey, String path, Map<String, Object> body) {
        String resp = rest.postForObject(baseUrl + path, new HttpEntity<>(body, headers(apiKey)), String.class);
        return parse(resp);
    }

    private JsonNode get(String apiKey, String path) {
        var entity = new HttpEntity<>(headers(apiKey));
        String resp = rest.exchange(baseUrl + path, org.springframework.http.HttpMethod.GET, entity, String.class).getBody();
        return parse(resp);
    }

    private static HttpHeaders headers(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(apiKey);
        return headers;
    }

    private static JsonNode parse(String resp) {
        if (resp == null || resp.isBlank()) return null;
        try {
            return MAPPER.readTree(resp);
        } catch (Exception e) {
            return null;
        }
    }

    /** Pull Vapi's {@code message} out of an error body, falling back to the HTTP status. */
    private static String errorMessage(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node.hasNonNull("message")) {
                JsonNode m = node.get("message");
                return m.isArray() && !m.isEmpty() ? m.get(0).asText() : m.asText();
            }
            if (node.hasNonNull("error")) return node.get("error").asText();
        } catch (Exception ignored) {
            // not JSON — fall through to the HTTP status
        }
        return "HTTP " + e.getStatusCode().value() + " — " + e.getStatusText();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    private static void put(Map<String, Object> body, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        body.put(key, value);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
