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
        UNREACHABLE,
        /**
         * The provider authenticated the key and then refused the request.
         *
         * <p>Distinct from both: the key is not in question, and waiting will not help. It means
         * this account cannot do what we asked — the wrong kind of key, a plan that lacks the
         * endpoint, a region restriction. The only useful thing we can do is repeat what the
         * provider said, because they know why and we do not.
         */
        REFUSED
    }

    /**
     * One model this key may use, and whether it can do what the assistant needs.
     *
     * <p>{@code chat} means: it generates text from a prompt, so an agent turn could run on it.
     * A provider's model list is every model the account can reach across ALL modalities —
     * Groq lists Whisper (speech-to-text) and Orpheus (text-to-speech) beside its chat models,
     * OpenAI lists embeddings and image models — and offering those as equal choices is a trap:
     * pick one and every turn fails with a provider error nobody can act on.
     *
     * @param id    the model id to send upstream
     * @param chat  false when we can tell it is not a text-generating model
     */
    /**
     * One model a key may use, plus whatever the provider chose to say about it.
     *
     * <p>Every field beyond {@code id} and {@code chat} is the PROVIDER'S own answer, nullable
     * because they disagree about what to publish: Google ships a human {@code description} and
     * both token limits, Groq reports {@code context_window} and {@code max_completion_tokens},
     * Anthropic only a {@code display_name}, OpenAI close to nothing. We report what they say
     * and stay silent where they say nothing — a blank here means the provider did not tell us,
     * which is a fact worth showing rather than a gap to fill with a guess.
     */
    public record ModelInfo(String id, boolean chat, String displayName, String description,
            Integer contextTokens, Integer maxOutputTokens, String ownedBy) {

        public ModelInfo(String id, boolean chat) {
            this(id, chat, null, null, null, null, null);
        }
    }

    /**
     * @param models models this key may use, best-effort and possibly empty — a provider
     *               that authenticates the key but lists nothing is still {@code OK}
     */
    public record Result(Outcome outcome, List<ModelInfo> models, String message) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }

        /** Just the ids, for validation and for callers that do not care about capability. */
        public List<String> modelIds() {
            return models.stream().map(ModelInfo::id).toList();
        }
    }

    /**
     * Whether a key can be put on a header at all: visible ASCII only, no control characters.
     *
     * <p>This check exists to keep the key OUT OF OUR LOGS. {@code HttpRequest.Builder.header()}
     * validates the value itself and throws an {@code IllegalArgumentException} whose message
     * quotes the offending value in full — so one stray newline in a pasted key, and the key
     * lands in the exception, the stack trace and the log. Checking first means the bad value
     * never reaches the builder.
     */
    private static boolean headerSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E) return false;  // control chars, space, tab, CR, LF, non-ASCII
        }
        return true;
    }

    public Result verify(AiProviderCatalog.Provider provider, String apiKey) {
        if (apiKey == null || apiKey.isEmpty() || !headerSafe(apiKey)) {
            // Never echo the value — not even a fragment. This is almost always a copy-paste
            // that caught a newline or a smart quote.
            return new Result(Outcome.INVALID, List.of(),
                    "That key contains characters " + provider.label() + " keys never have. "
                            + "Copy it again, without any spaces or line breaks.");
        }
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
        if (status == 400 && rejectsKey(res.body())) {
            // Google's Generative Language API returns 400 INVALID_ARGUMENT with reason
            // API_KEY_INVALID for a wrong, mistyped or deleted key. Reported as UNREACHABLE it
            // becomes a 503 "try again in a moment", and the workspace can never connect.
            return new Result(Outcome.INVALID, List.of(),
                    provider.label() + " rejected this key. Check you copied it in full, and that it hasn't been revoked.");
        }
        if (status / 100 == 4) {
            // A 4xx that is not 401/403/429 means the key authenticated and the REQUEST was
            // refused. Reported as UNREACHABLE this became a 503 "try again in a moment" — advice
            // that can never work — while the provider's own explanation, the one actionable
            // thing in the exchange, was dropped on the floor.
            String said = providerMessage(res.body());
            log.warn("ai: {} refused the models request with {}: {}", provider.id(), status,
                    said == null ? "(no message in body)" : said);
            return new Result(Outcome.REFUSED, List.of(), said == null
                    ? provider.label() + " refused this request (" + status + "). Check this key has access to "
                            + provider.label() + "'s API."
                    : provider.label() + " said: " + said);
        }
        if (status / 100 != 2) {
            log.warn("ai: {} models probe returned {}: {}", provider.id(), status, providerMessage(res.body()));
            return new Result(Outcome.UNREACHABLE, List.of(),
                    provider.label() + " returned an unexpected error (" + status + "). Try again in a moment.");
        }
        return new Result(Outcome.OK, parseModels(res.body()), null);
    }

    /**
     * The provider's own error message, if their body carries one.
     *
     * <p>Every provider here nests it differently ({@code error.message} for Anthropic, OpenAI and
     * Google; a bare {@code message} elsewhere), so this walks the shapes we know and gives up
     * quietly. It is the provider's text about their own refusal — it contains nothing of ours,
     * and never the key, which is why it is safe both to log and to show.
     */
    private String providerMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = json.readTree(body);
            for (JsonNode candidate : new JsonNode[] {root.path("error").path("message"),
                                                      root.path("message"),
                                                      root.path("error").path("status"),
                                                      root.path("detail")}) {
                String text = candidate.isTextual() ? candidate.asText().trim() : "";
                // Bounded: a provider that returns an essay must not fill our logs or a dialog.
                if (!text.isEmpty()) return text.length() > 300 ? text.substring(0, 300) + "…" : text;
            }
        } catch (Exception e) {  // NOSONAR - an unparseable body is not worth failing over
            log.debug("ai: could not read the provider's error body: {}", e.toString());
        }
        return null;
    }

    /**
     * Whether a client-error body is the provider saying "this key is not valid".
     *
     * <p>Only consulted for a 400, and only for phrases a provider actually uses — a body that
     * merely mentions a key must not cost a workspace its connection.
     */
    private boolean rejectsKey(String body) {
        if (body == null) return false;
        String b = body.toLowerCase(java.util.Locale.ROOT);
        return b.contains("api_key_invalid") || b.contains("api key not valid")
                || b.contains("invalid api key") || b.contains("invalid_api_key")
                || b.contains("api key expired");
    }

    /**
     * Pull model ids out of a provider's models response.
     *
     * <p>Three shapes, one walk: OpenAI/Groq/Anthropic return {@code {"data":[{"id":…}]}} and
     * Google returns {@code {"models":[{"name":"models/…"}]}}. A provider adding a field must
     * not break connecting, so anything unparseable degrades to an empty list — the key is
     * still good, the workspace just gets the provider's default model instead of a picker.
     */
    private List<ModelInfo> parseModels(String body) {
        List<ModelInfo> out = new ArrayList<>();
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
                    if (id != null && !id.isBlank()) out.add(describeModel(item, id));
                }
            }
        } catch (Exception e) {  // NOSONAR - an unreadable list must not fail a valid key
            log.debug("ai: could not parse the model list: {}", e.toString());
            return List.of();
        }
        out.sort(Comparator.comparing(ModelInfo::id));
        return List.copyOf(out);
    }

    /**
     * Read back what the provider published about one model.
     *
     * <p>Field names differ per provider and none of them is required, so every lookup is
     * best-effort and absence is normal. Nothing here is derived or inferred — if a value is
     * present it is because the provider sent it.
     */
    private ModelInfo describeModel(JsonNode item, String id) {
        return new ModelInfo(
                id,
                isChatModel(item, id),
                // Google: displayName · Anthropic: display_name.
                text(item, "displayName", "display_name"),
                // Google is the only one that ships prose today.
                text(item, "description"),
                // Google: inputTokenLimit · Groq/OpenAI: context_window.
                number(item, "inputTokenLimit", "context_window"),
                // Google: outputTokenLimit · Groq/OpenAI: max_completion_tokens.
                number(item, "outputTokenLimit", "max_completion_tokens"),
                text(item, "owned_by", "ownedBy"));
    }

    /** First of these fields the provider actually sent, trimmed and bounded; null if none. */
    private static String text(JsonNode item, String... fields) {
        for (String f : fields) {
            String v = item.path(f).asText(null);
            if (v != null && !v.isBlank()) {
                String t = v.trim();
                return t.length() > 400 ? t.substring(0, 400) : t;
            }
        }
        return null;
    }

    /** First of these numeric fields the provider actually sent; null if none or not a number. */
    private static Integer number(JsonNode item, String... fields) {
        for (String f : fields) {
            JsonNode n = item.path(f);
            if (n.isNumber() && n.asInt() > 0) return n.asInt();
        }
        return null;
    }

    /**
     * Whether this entry looks like something an agent turn could run on.
     *
     * <p><b>Ask the provider before guessing at the name.</b> Most of them say so structurally:
     * Groq and OpenAI report {@code max_completion_tokens} on models that generate text, and
     * Google lists {@code generateContent} in {@code supportedGenerationMethods}. Where a
     * provider says nothing we fall back to name families, which is a guess about names they own
     * — the same kind of guess that wrongly refused a real Google key — so it is used only to
     * DEMOTE a model into a second group, never to hide it.
     *
     * <p>Unknown therefore resolves to {@code false}: a demoted chat model is still one click
     * away and obvious, whereas a promoted speech-to-text model is a trap that fails every turn.
     */
    private boolean isChatModel(JsonNode item, String id) {
        // 1. Google is explicit about it.
        JsonNode methods = item.path("supportedGenerationMethods");
        if (methods.isArray()) {
            for (JsonNode m : methods) {
                if ("generateContent".equals(m.asText())) return true;
            }
            return false;  // it listed its methods and generateContent was not among them
        }
        // 2. OpenAI-shaped providers report an output budget only for models that produce text.
        for (String field : new String[] {"max_completion_tokens", "max_output_tokens", "max_tokens"}) {
            JsonNode n = item.path(field);
            if (n.isNumber() && n.asLong() > 0) return !isKnownNonChatName(id);
        }
        // 3. Nothing structural to go on — fall back to the name, and demote when unsure.
        return isKnownChatName(id) && !isKnownNonChatName(id);
    }

    /** Name families that are certainly NOT text generation, across the providers we support. */
    private static boolean isKnownNonChatName(String id) {
        String n = id.toLowerCase(java.util.Locale.ROOT);
        return java.util.stream.Stream.of(
                        "whisper", "transcribe", "tts", "speech", "orpheus", "audio",
                        "embed", "rerank", "moderation", "guard", "safeguard",
                        "dall-e", "imagen", "veo", "image", "video")
                .anyMatch(n::contains);
    }

    /** Name families we are confident DO generate text, for providers that tell us nothing. */
    private static boolean isKnownChatName(String id) {
        String n = id.toLowerCase(java.util.Locale.ROOT);
        return java.util.stream.Stream.of(
                        "gpt", "claude", "llama", "gemini", "mistral", "mixtral", "qwen",
                        "deepseek", "gemma", "kimi", "allam", "command", "grok", "phi")
                .anyMatch(n::contains);
    }
}
