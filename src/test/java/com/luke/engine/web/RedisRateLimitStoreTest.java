package com.luke.engine.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luke.engine.web.RedisRateLimitStore.WindowCounter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #55: the Redis-backed store's fixed-window logic (unit-tested against an in-memory WindowCounter
 * standing in for Redis INCR), plus the fail-safe fallback to the in-memory store when the backend
 * throws.
 */
class RedisRateLimitStoreTest {

    private static final long WINDOW = 60_000L;
    private final RateLimitStore fallback = new InMemoryRateLimitStore(1000);

    /** A WindowCounter that behaves like Redis INCR: per-bucket monotonic counts. */
    private WindowCounter fakeRedis(Map<String, Long> counts) {
        return (bucketKey, wm) -> counts.merge(bucketKey, 1L, Long::sum);
    }

    @Test
    void allowsUpToMaxThenReportsRetryAfter() {
        RedisRateLimitStore store = new RedisRateLimitStore(WINDOW, fakeRedis(new HashMap<>()), fallback);
        long now = 1_700_000_000_000L;
        assertEquals(-1, store.retryAfterSeconds("k", 2, now));
        assertEquals(-1, store.retryAfterSeconds("k", 2, now));
        long retryAfter = store.retryAfterSeconds("k", 2, now); // 3rd over the max of 2
        assertTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After within the window: " + retryAfter);
    }

    @Test
    void differentKeysAreIndependent() {
        RedisRateLimitStore store = new RedisRateLimitStore(WINDOW, fakeRedis(new HashMap<>()), fallback);
        long now = 1_700_000_000_000L;
        assertEquals(-1, store.retryAfterSeconds("a", 1, now));
        assertEquals(-1, store.retryAfterSeconds("b", 1, now)); // its own bucket
        assertTrue(store.retryAfterSeconds("a", 1, now) >= 1);  // a is now over
    }

    @Test
    void countResetsInTheNextWindow() {
        RedisRateLimitStore store = new RedisRateLimitStore(WINDOW, fakeRedis(new HashMap<>()), fallback);
        long now = 1_700_000_000_000L;
        assertEquals(-1, store.retryAfterSeconds("k", 1, now));
        assertTrue(store.retryAfterSeconds("k", 1, now) >= 1);          // over in this window
        assertEquals(-1, store.retryAfterSeconds("k", 1, now + WINDOW)); // next window → fresh bucket
    }

    @Test
    void fallsBackToInMemoryWhenRedisThrows() {
        WindowCounter boom = (bucketKey, wm) -> { throw new RuntimeException("redis down"); };
        RedisRateLimitStore store = new RedisRateLimitStore(WINDOW, boom, fallback);
        long now = 1_700_000_000_000L;
        // Degrades to the in-memory fallback (max 1): first allowed, second limited — still throttles.
        assertEquals(-1, store.retryAfterSeconds("fk", 1, now));
        assertTrue(store.retryAfterSeconds("fk", 1, now) >= 1, "fallback still enforces the limit");
    }
}
