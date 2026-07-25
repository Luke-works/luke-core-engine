package com.luke.engine.web;

/**
 * A fixed-window rate-limit decision for a key (#55). {@code max} is passed per call because the
 * public embed surface uses different caps for different keys (render vs submit, per-token vs
 * per-IP). Two implementations: {@link InMemoryRateLimitStore} (per-instance — the default and the
 * fallback) and {@link RedisRateLimitStore} (shared across all replicas when {@code REDIS_URL} is set).
 */
public interface RateLimitStore {

    /**
     * Record a hit for {@code key} against a cap of {@code max} per window, at {@code nowMillis}.
     *
     * @return {@code -1} if the hit is allowed; otherwise the Retry-After in seconds (&ge; 1).
     */
    long retryAfterSeconds(String key, int max, long nowMillis);
}
