package com.luke.engine.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI configuration — where the agent fleet lives and how we authenticate to it.
 *
 * <p><b>Deliberately not a provider key.</b> Under bring-your-own-key there is no platform LLM
 * key anywhere in this service: each workspace's key is decrypted out of {@code luke_secrets}
 * on its way to a turn, and nothing is billed to Lukeflow. The only credential here is the
 * shared service key for luke-agents itself.
 *
 * <p>Config-gated, like payments. With {@code LUKE_AI_AGENTS_URL} unset {@link #enabled()} is
 * false and the whole feature self-disables: the connect page reports it as unavailable and the
 * agent proxy 404s. No environment is forced to run an agent fleet.
 */
@Component
public class AiProperties {

    private final String agentsUrl;
    private final String serviceKey;
    private final int timeoutMs;

    public AiProperties(
            @Value("${luke.ai.agents-url:${VITE_FORM_AGENT_URL:}}") String agentsUrl,
            // Falls back to AGENTS_API_KEY, which is the name the agents service reads it under.
            // One generated value in a shared env group then serves both sides, so the engine's
            // key and the fleet's can never drift apart and 401 every turn.
            @Value("${luke.ai.service-key:${AGENTS_API_KEY:}}") String serviceKey,
            @Value("${luke.ai.timeout-ms:90000}") int timeoutMs) {
        this.agentsUrl = trimToNull(agentsUrl);
        this.serviceKey = trimToNull(serviceKey);
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 90_000;
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Whether the AI features exist on this deployment.
     *
     * <p>Only asks whether the fleet is reachable. Whether a given <em>workspace</em> can use it
     * is a separate question, answered by that workspace's own connected provider — the whole
     * point of bring-your-own-key.
     */
    public boolean enabled() {
        return agentsUrl != null;
    }

    /** The agents base URL with no trailing slash, so path joins are unambiguous. */
    public String base() {
        if (agentsUrl == null) return "";
        return agentsUrl.endsWith("/") ? agentsUrl.substring(0, agentsUrl.length() - 1) : agentsUrl;
    }

    /** Shared service key sent as {@code X-Agents-Key}; null when unset (local dev). */
    public String serviceKey() {
        return serviceKey;
    }

    public int timeoutMs() {
        return timeoutMs;
    }
}
