package com.luke.engine.workflow.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.config.RestTemplates;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
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

/**
 * HTTP {@link NangoClient} against Nango Cloud. Authenticates with the platform
 * secret key (Bearer) — a single server-side secret, never per-tenant and never sent
 * to the browser. Mirrors {@code VapiClient}'s thin-REST style.
 *
 * <p>The secret key is read at request time, so a missing key never crashes boot; a
 * connect attempt without a key fails with a clear {@link IntegrationException}.
 */
@Component
public class NangoApiClient implements NangoClient {

    private static final Logger log = LoggerFactory.getLogger(NangoApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest = RestTemplates.withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(15));

    @Value("${luke.workflow.nango.base-url:https://api.nango.dev}")
    private String baseUrl;

    @Value("${luke.workflow.nango.secret-key:}")
    private String secretKey;

    @Override
    public ConnectSession createConnectSession(String providerConfigKey, String endUserId,
            String endUserEmail, String organizationId) {
        requireKey();

        Map<String, Object> endUser = new LinkedHashMap<>();
        endUser.put("id", endUserId);
        put(endUser, "email", endUserEmail);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("end_user", endUser);
        if (organizationId != null && !organizationId.isBlank()) {
            body.put("organization", Map.of("id", organizationId));
        }
        if (providerConfigKey != null && !providerConfigKey.isBlank()) {
            body.put("allowed_integrations", List.of(providerConfigKey));
        }

        try {
            JsonNode resp = post("/connect/sessions", body);
            JsonNode data = (resp != null && resp.has("data")) ? resp.get("data") : resp;
            String token = text(data, "token");
            if (token == null) {
                throw new IntegrationException("Nango did not return a connect session token");
            }
            return new ConnectSession(token, text(data, "expires_at"));
        } catch (HttpStatusCodeException e) {
            throw new IntegrationException("Nango connect session failed: " + errorMessage(e));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> triggerAction(String providerConfigKey, String nangoConnectionId,
            String action, Map<String, Object> input) {
        requireKey();
        HttpHeaders headers = headers();
        headers.set("Connection-Id", nangoConnectionId);
        headers.set("Provider-Config-Key", providerConfigKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action_name", action);
        body.put("input", input == null ? Map.of() : input);

        try {
            String resp = rest.postForObject(baseUrl + "/action/trigger", new HttpEntity<>(body, headers), String.class);
            JsonNode node = parse(resp);
            return node == null ? Map.of() : MAPPER.convertValue(node, Map.class);
        } catch (HttpStatusCodeException e) {
            int code = e.getStatusCode().value();
            String msg = errorMessage(e);
            if (code == 401 || code == 403) throw new NangoAuthException(msg);
            if (code == 429) throw new NangoRateLimitException(msg);
            if (code >= 500) throw new NangoServerException(msg);
            throw new NangoActionException(msg);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // connection reset / timeout — transient, let the caller retry.
            throw new NangoServerException("Nango request failed: " + e.getMessage());
        }
    }

    @Override
    public void deleteConnection(String providerConfigKey, String nangoConnectionId) {
        if (isBlank(secretKey) || isBlank(nangoConnectionId)) return;
        try {
            String url = baseUrl + "/connection/" + nangoConnectionId
                    + "?provider_config_key=" + providerConfigKey;
            rest.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers()), String.class);
        } catch (Exception e) {
            // Best-effort: a revoke failure must not block removing our row.
            log.warn("Nango deleteConnection failed for {}: {}", nangoConnectionId, e.getMessage());
        }
    }

    /* ── HTTP plumbing ──────────────────────────────────────────── */

    private JsonNode post(String path, Map<String, Object> body) {
        String resp = rest.postForObject(baseUrl + path, new HttpEntity<>(body, headers()), String.class);
        return parse(resp);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(secretKey);
        return headers;
    }

    private void requireKey() {
        if (isBlank(secretKey)) {
            throw new IntegrationException("Nango secret key is not configured (luke.workflow.nango.secret-key)");
        }
    }

    private static JsonNode parse(String resp) {
        if (resp == null || resp.isBlank()) return null;
        try {
            return MAPPER.readTree(resp);
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorMessage(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node.hasNonNull("error")) {
                JsonNode err = node.get("error");
                return err.isObject() && err.hasNonNull("message") ? err.get("message").asText() : err.asText();
            }
            if (node.hasNonNull("message")) return node.get("message").asText();
        } catch (Exception ignored) {
            // not JSON — fall through
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
