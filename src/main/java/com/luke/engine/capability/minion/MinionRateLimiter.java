package com.luke.engine.capability.minion;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fixed 1-minute-window rate limiter for minion calls, keyed per (caller + operation). Minions proxy
 * to metered, paid third-party APIs (geocoding, etc.), so an uncapped endpoint is both a cost and an
 * abuse vector. Mirrors the embed-submit limiter: evict only STALE windows so a flood of fresh keys
 * can never reset the current minute's counters (a global-bypass an attacker could otherwise trigger).
 */
@Component
public class MinionRateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** Count this call against {@code key}; throw 429 once it exceeds {@code max} within the minute. */
    public void check(String key, int max) {
        long minute = System.currentTimeMillis() / 60_000L;
        Window w = windows.compute(key, (k, cur) -> (cur == null || cur.minute != minute) ? new Window(minute) : cur);
        if (w.count.incrementAndGet() > max) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, try again shortly.");
        }
        if (windows.size() > 50_000) {
            windows.values().removeIf(win -> win.minute != minute);
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
