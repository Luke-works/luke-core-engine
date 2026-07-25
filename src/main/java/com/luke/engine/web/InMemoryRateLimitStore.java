package com.luke.engine.web;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-instance fixed 1-minute-window rate limiter (#55) — the original
 * {@link FixedWindowRateLimiter} logic, extracted behind {@link RateLimitStore} so a Redis-backed
 * store can slot in when {@code REDIS_URL} is set. It is also the fallback the Redis store degrades
 * to when Redis is unreachable, so behaviour is consistent either way.
 *
 * <p>Eviction drops only STALE windows (never the current minute's counters) so a flood of fresh
 * keys can't reset live counters — a global-bypass an attacker could otherwise trigger.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    private static final long WINDOW_MILLIS = 60_000L;

    private final int maxKeys;
    // key -> [windowBucket, count]
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    public InMemoryRateLimitStore(int maxKeys) {
        this.maxKeys = Math.max(1, maxKeys);
    }

    @Override
    public long retryAfterSeconds(String key, int max, long nowMillis) {
        long bucket = nowMillis / WINDOW_MILLIS;
        long[] out = {-1};
        windows.compute(key, (k, w) -> {
            long[] nw = (w == null || w[0] != bucket) ? new long[] {bucket, 0} : w;
            nw[1]++;
            if (nw[1] > max) {
                long resetInMillis = (bucket + 1) * WINDOW_MILLIS - nowMillis;
                out[0] = Math.max(1, (resetInMillis + 999) / 1000); // ceil to seconds, min 1
            }
            return nw;
        });
        if (windows.size() > maxKeys) {
            windows.values().removeIf(win -> win[0] != bucket); // evict only stale windows
        }
        return out[0];
    }
}
