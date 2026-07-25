package com.luke.engine.web;

import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared fixed 1-minute-window rate limiter (#55) — consolidates the identical logic that was
 * copy-pasted into {@code FormEmbedController} and {@code MinionRateLimiter}. Per key: at most
 * {@code max} hits per minute; further hits get a {@code Retry-After}.
 *
 * <p>In-memory and therefore <b>per-instance</b>. Eviction drops only STALE windows (never the
 * current minute's counters) so a flood of fresh keys can't reset live counters — a global-bypass
 * an attacker could otherwise trigger.
 *
 * <p>A cross-replica (Redis-backed) store is the same {@code REDIS_URL} pattern shipped on the
 * gateway (luke-auth-engine #56); this class is the seam it would slot behind.
 */
@Component
public class FixedWindowRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_KEYS = 50_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * Count a hit against {@code key}.
     *
     * @return {@code -1} if allowed; otherwise the Retry-After in seconds (&ge; 1).
     */
    public long retryAfterSeconds(String key, int max) {
        long now = System.currentTimeMillis();
        long minute = now / WINDOW_MILLIS;
        Window w = windows.compute(key, (k, cur) -> (cur == null || cur.minute != minute) ? new Window(minute) : cur);
        long count = w.count.incrementAndGet();
        if (windows.size() > MAX_KEYS) {
            windows.values().removeIf(win -> win.minute != minute); // evict only stale windows
        }
        if (count > max) {
            long resetInMillis = (minute + 1) * WINDOW_MILLIS - now;
            return Math.max(1, (resetInMillis + 999) / 1000); // ceil to seconds, min 1
        }
        return -1;
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

    private static final class Window {
        final long minute;
        final AtomicInteger count = new AtomicInteger(0);

        Window(long minute) {
            this.minute = minute;
        }
    }
}
