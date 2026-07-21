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
 * Postmark <em>Account</em> API client — used to create a Server per tenant. This
 * uses the account-level token ({@code X-Postmark-Account-Token}), which is a
 * different, more privileged credential than the per-server send token. The newly
 * created server's send token comes back in the response and is what we store on
 * {@link EmailServer}.
 *
 * <p>Like {@link PostmarkClient}, a Postmark error is captured in the result, not
 * thrown; a missing account token is the one hard configuration error.
 */
@Component
public class PostmarkAccountClient {

    private static final Logger log = LoggerFactory.getLogger(PostmarkAccountClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest = RestTemplates.withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(15));

    @Value("${luke.email.postmark.base-url:https://api.postmarkapp.com}")
    private String baseUrl;

    @Value("${luke.email.postmark.account-token:}")
    private String accountToken;

    /** Outcome of a create-server call. ok=true → serverId + serverToken set. */
    public record CreateServerResult(boolean ok, Long serverId, String serverToken, String error) {}

    public boolean isConfigured() {
        return accountToken != null && !accountToken.isBlank();
    }

    /**
     * Create a Postmark Server named {@code name} (optionally tinted {@code color}
     * in the Postmark UI). Returns the new server's id and its primary send token.
     */
    public CreateServerResult createServer(String name, String color) {
        if (!isConfigured()) {
            return new CreateServerResult(false, null, null,
                    "Postmark account token is not configured (set POSTMARK_ACCOUNT_TOKEN)");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            headers.set("X-Postmark-Account-Token", accountToken);

            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("Name", name);
            if (color != null && !color.isBlank()) reqBody.put("Color", color);

            JsonNode resp = rest.postForObject(
                    baseUrl + "/servers", new HttpEntity<>(reqBody, headers), JsonNode.class);

            Long serverId = resp != null && resp.hasNonNull("ID") ? resp.get("ID").asLong() : null;
            String token = extractToken(resp);
            if (serverId != null && token != null) {
                return new CreateServerResult(true, serverId, token, null);
            }
            return new CreateServerResult(false, serverId, null,
                    "Postmark created no usable server token in the response");
        } catch (HttpStatusCodeException e) {
            String message = messageFrom(e.getResponseBodyAsString(),
                    "HTTP " + e.getStatusCode().value() + " — " + e.getStatusText());
            log.warn("Postmark create-server failed: {}", message);
            return new CreateServerResult(false, null, null, message);
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Postmark create-server errored: {}", detail);
            return new CreateServerResult(false, null, null, detail);
        }
    }

    /** Outcome of setting a server's inbound webhook. */
    public record InboundHookResult(boolean ok, String inboundAddress, String error) {}

    /**
     * Point a tenant's Postmark server at our public inbound webhook by setting its
     * {@code InboundHookUrl} (account API, {@code PUT /servers/{id}}). Returns the server's
     * Postmark inbound address ({@code <hash>@inbound.postmarkapp.com}) so callers can show
     * where to forward/MX mail. Idempotent — safe to re-set the same URL.
     */
    public InboundHookResult setInboundHook(long serverId, String inboundHookUrl) {
        if (!isConfigured()) {
            return new InboundHookResult(false, null,
                    "Postmark account token is not configured (set POSTMARK_ACCOUNT_TOKEN)");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            headers.set("X-Postmark-Account-Token", accountToken);

            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("InboundHookUrl", inboundHookUrl);

            JsonNode resp = rest.exchange(
                    baseUrl + "/servers/" + serverId,
                    org.springframework.http.HttpMethod.PUT,
                    new HttpEntity<>(reqBody, headers),
                    JsonNode.class).getBody();
            String inboundAddress = resp != null && resp.hasNonNull("InboundAddress")
                    ? resp.get("InboundAddress").asText() : null;
            return new InboundHookResult(true, inboundAddress, null);
        } catch (HttpStatusCodeException e) {
            String message = messageFrom(e.getResponseBodyAsString(),
                    "HTTP " + e.getStatusCode().value() + " — " + e.getStatusText());
            log.warn("Postmark set-inbound-hook failed: {}", message);
            return new InboundHookResult(false, null, message);
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Postmark set-inbound-hook errored: {}", detail);
            return new InboundHookResult(false, null, detail);
        }
    }

    /** The send token is the first entry of "ApiTokens" (legacy: "ServerToken"). */
    private static String extractToken(JsonNode resp) {
        if (resp == null) return null;
        JsonNode tokens = resp.get("ApiTokens");
        if (tokens != null && tokens.isArray() && !tokens.isEmpty()) {
            return tokens.get(0).asText();
        }
        if (resp.hasNonNull("ServerToken")) return resp.get("ServerToken").asText();
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
}
