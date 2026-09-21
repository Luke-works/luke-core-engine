package com.luke.engine.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks a provider whether a key works, and which models it may use.
 *
 * <p>One authenticated GET to the provider's models endpoint answers both questions, which is
 * why it is the check we run when a workspace connects: it proves the key without spending a
 * token, and it returns the model list the connect page offers — so a workspace picks from
 * what their own account can actually run, not from a list we hardcoded.
 *
 * <p>Outcomes are deliberately separated. {@code INVALID} means the provider rejected the key
 * and the workspace must fix it; {@code UNREACHABLE} means we could not get an answer, which is
 * our problem, not theirs, and must never mark a good key bad.
 */
@Component
public class AiProviderProbe {

    private static final Logger log = LoggerFactory.getLogger(AiProviderProbe.class);

    /** Anthropic requires a dated API version on every request. */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)  // a provider redirect must never carry the key elsewhere
            .build();
    private final ObjectMapper json = new ObjectMapper();

    public enum Outcome {
        /** The key works. */
        OK,
        /** The provider says no — revoked, mistyped, or for a different account. */
        INVALID,
        /** We could not reach the provider, or it failed. Says nothing about the key. */
        UNREACHABLE
    }

    /**
     * @param models model ids this key may use, best-effort and possibly empty — a provider
     *               that authenticates the key but lists nothing is still {@code OK}
     */
    public record Result(Outcome outcome, List<String> models, String message) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }

    public Result verify(AiProviderCatalog.Provider provider, String apiKey) {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET();
        switch (provider.auth()) {
            case BEARER -> req.uri(URI.create(provider.modelsUrl())).header("Authorization", "Bearer " + apiKey);
            case X_API_KEY -> req.uri(URI.create(provider.modelsUrl()))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION);
            // The key is a query parameter for Google. URL-encoding it matters: an unencoded
            // key containing a reserved character would silently become a different request.
            case QUERY -> req.uri(URI.create(provider.modelsUrl() + "?key="
                    + java.net.URLEncoder.encode(apiKey, java.nio.charset.StandardCharsets.UTF_8)
                    + "&pageSize=200"));
        }

        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Never log the key, and never conclude anything about it from our own failure.
            log.warn("ai: could not reach {} to verify a key: {}", provider.id(), e.toString());
            return new Result(Outcome.UNREACHABLE, List.of(),
                    "Couldn't reach " + provider.label() + " just now. Try again in a moment.");
        }

        int status = res.statusCode();
        if (status == 401 || status == 403) {
            return new Result(Outcome.INVALID, List.of(),
                    provider.label() + " rejected this key. Check you copied it in full, and that it hasn't been revoked.");
        }
        if (status == 429) {
            // The key authenticated — the account is just rate-limited right now. Treating this
            // as INVALID would disconnect a working workspace during a traffic spike.
            return new Result(Outcome.UNREACHABLE, List.of(),
                    provider.label() + " is rate-limiting this account right now. The key looks fine; try again shortly.");
        }
        if (status / 100 != 2) {
            log.warn("ai: {} models probe returned {}", provider.id(), status);
            return new Result(Outcome.UNREACHABLE, List.of(),
                    provider.label() + " returned an unexpected error (" + status + "). Try again in a moment.");
        }
        return new Result(Outcome.OK, parseModels(res.body()), null);
    }

    /**
     * Pull model ids out of a provider's models response.
     *
     * <p>Three shapes, one walk: OpenAI/Groq/Anthropic return {@code {"data":[{"id":…}]}} and
     * Google returns {@code {"models":[{"name":"models/…"}]}}. A provider adding a field must
     * not break connecting, so anything unparseable degrades to an empty list — the key is
     * still good, the workspace just gets the provider's default model instead of a picker.
     */
    private List<String> parseModels(String body) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode root = json.readTree(body);
            JsonNode items = root.has("data") ? root.get("data") : root.get("models");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    String id = item.path("id").asText(null);
                    if (id == null || id.isBlank()) {
                        // Google: "models/gemini-2.0-flash" — the caller wants the bare id.
                        String name = item.path("name").asText("");
                        id = name.startsWith("models/") ? name.substring("models/".length()) : name;
                    }
                    if (id != null && !id.isBlank()) out.add(id);
                }
            }
        } catch (Exception e) {  // NOSONAR - an unreadable list must not fail a valid key
            log.debug("ai: could not parse the model list: {}", e.toString());
            return List.of();
        }
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }
}
