package com.luke.engine.ai;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The AI providers a workspace can bring a key for, and everything we know about each one
 * without talking to it.
 *
 * <p>Every base URL here is a compile-time constant. A tenant chooses a provider from this
 * list by id — they never supply a URL — so no tenant input can aim an outbound request at
 * a host of their choosing.
 *
 * <p>Ollama is deliberately absent: it is a local dev backend with no account and no key
 * behind it, so there is nothing to "bring".
 */
public final class AiProviderCatalog {

    /** How a provider expects its key to be presented on a request. */
    public enum Auth {
        /** {@code Authorization: Bearer <key>} — Groq and OpenAI. */
        BEARER,
        /** {@code x-api-key: <key>} plus a version header — Anthropic. */
        X_API_KEY,
        /** {@code ?key=<key>} on the query string — Google. */
        QUERY
    }

    /**
     * @param id           stable lowercase id, sent to luke-agents as {@code X-AI-Provider}
     * @param label        how we name it to a human
     * @param modelsUrl    a cheap authenticated GET that both proves the key and lists the
     *                     models that key may actually use
     * @param auth         how the key rides on that request
     * @param defaultModel used when the workspace does not pick one
     * @param keyPrefix    the prefix a key of this provider starts with today, for a fast,
     *                     friendly "that's the wrong provider's key" before any network call
     * @param consoleUrl   where a human goes to create the key
     */
    public record Provider(String id, String label, String modelsUrl, Auth auth,
                           String defaultModel, String keyPrefix, String consoleUrl) {}

    public static final Provider GROQ = new Provider(
            "groq", "Groq",
            "https://api.groq.com/openai/v1/models", Auth.BEARER,
            "openai/gpt-oss-120b", "gsk_", "https://console.groq.com/keys");

    public static final Provider OPENAI = new Provider(
            "openai", "OpenAI",
            "https://api.openai.com/v1/models", Auth.BEARER,
            "gpt-5-nano", "sk-", "https://platform.openai.com/api-keys");

    public static final Provider ANTHROPIC = new Provider(
            "anthropic", "Anthropic",
            "https://api.anthropic.com/v1/models", Auth.X_API_KEY,
            "claude-haiku-4-5-20251001", "sk-ant-", "https://console.anthropic.com/settings/keys");

    public static final Provider GEMINI = new Provider(
            "gemini", "Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta/models", Auth.QUERY,
            "gemini-2.0-flash", "AIza", "https://aistudio.google.com/apikey");

    private static final List<Provider> ALL = List.of(GROQ, OPENAI, ANTHROPIC, GEMINI);

    /** The provider whose key format {@code key} matches, if any — for a better error message. */
    public static Optional<Provider> looksLikeKeyOf(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        // Anthropic first: sk-ant- would otherwise match OpenAI's looser sk- prefix.
        return ALL.stream()
                .sorted((a, b) -> Integer.compare(b.keyPrefix().length(), a.keyPrefix().length()))
                .filter(p -> looksLikeKeyFor(p, key))
                .findFirst();
    }

    private AiProviderCatalog() {}

    public static List<Provider> all() {
        return ALL;
    }

    public static Optional<Provider> find(String id) {
        if (id == null) return Optional.empty();
        String needle = id.trim().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(p -> p.id().equals(needle)).findFirst();
    }

    /**
     * Whether {@code key} looks like a key for {@code provider}.
     *
     * <p><b>Advisory only — never a reason to refuse a key.</b> It began as a pre-flight guard
     * that rejected a mismatched prefix before any network call, and that was wrong: a prefix is
     * a guess about a format the provider owns and may change, and the guess blocked a real
     * Google key that did not begin with {@code AIza}. Refusing on a heuristic while a
     * definitive check ({@link AiProviderProbe}, which asks the provider) sits one line below it
     * is backwards.
     *
     * <p>It still earns its place, but only for the message AFTER a refusal: when the provider
     * rejects a key that looks like another provider's, "that looks like an OpenAI key" is far
     * more useful than repeating the provider's 401. Anthropic is checked before OpenAI because
     * {@code sk-ant-} also starts with {@code sk-}.
     */
    public static boolean looksLikeKeyFor(Provider provider, String key) {
        if (key == null || key.isBlank()) return false;
        String k = key.trim();
        if (provider == OPENAI) {
            // An Anthropic key would otherwise pass OpenAI's looser "sk-" prefix.
            return k.startsWith("sk-") && !k.startsWith(ANTHROPIC.keyPrefix());
        }
        return k.startsWith(provider.keyPrefix());
    }
}
