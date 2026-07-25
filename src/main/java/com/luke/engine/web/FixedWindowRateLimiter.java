package com.luke.engine.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared fixed 1-minute-window rate limiter (#55) used by the public embed surface
 * ({@code FormEmbedController}). Per key: at most {@code max} hits per minute; further hits get a
 * {@code Retry-After}.
 *
 * <p>This is a thin facade over a {@link RateLimitStore}: {@link InMemoryRateLimitStore} per-instance
 * by default, or a {@link RedisRateLimitStore} shared across every replica when {@code REDIS_URL} is
 * set (chosen in {@code RateLimitConfig}) — so horizontal scale-out can't dilute the limit.
 */
@Component
public class FixedWindowRateLimiter {

    private final RateLimitStore store;

    @Autowired
    public FixedWindowRateLimiter(RateLimitStore store) {
        this.store = store;
    }

    /** Convenience for tests / standalone use: an in-memory per-instance store. */
    public FixedWindowRateLimiter() {
        this(new InMemoryRateLimitStore(50_000));
    }

    /**
     * Count a hit against {@code key}.
     *
     * @return {@code -1} if allowed; otherwise the Retry-After in seconds (&ge; 1).
     */
    public long retryAfterSeconds(String key, int max) {
        return store.retryAfterSeconds(key, max, System.currentTimeMillis());
    }

    /**
     * Enforce the limit: on over-limit, set {@code Retry-After} on the response and throw a 429.
     * (The header is set on the raw response so it survives the global error handler.)
     */
    public void enforce(String key, int max, HttpServletResponse response) {
        long retryAfter = retryAfterSeconds(key, max);
        if (retryAfter >= 0) {
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, try again shortly.");
        }
    }
}
