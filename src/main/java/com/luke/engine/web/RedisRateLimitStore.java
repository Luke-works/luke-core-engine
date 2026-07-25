package com.luke.engine.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A shared, cross-replica fixed-window rate limiter backed by Redis (#55).
 *
 * <p>The count for {@code (key, window-bucket)} is kept in Redis, so the limit is enforced
 * <b>globally</b> across every core-engine replica — horizontal scale-out can't be used to dilute
 * the per-endpoint limit on the public embed surface. Same fixed-window semantics as
 * {@link InMemoryRateLimitStore}, so behaviour is consistent whether Redis is configured or not.
 *
 * <p><b>Fail-safe:</b> if the Redis call throws (Redis down / network blip), this degrades to the
 * injected in-memory fallback for that hit rather than failing the request open — throttling still
 * happens per-instance until Redis recovers.
 *
 * <p>The atomic increment + first-hit expiry is a tiny server-side script (so a counted window
 * always gets a TTL and can't leak); it is provided as a {@link WindowCounter} so the fixed-window
 * logic here is unit-testable without a live Redis.
 */
public class RedisRateLimitStore implements RateLimitStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);

    /** Atomically increments the bucket's counter (setting its TTL on the first hit) and returns
     *  the new count. Throws on any backend failure so the store can fall back. */
    @FunctionalInterface
    public interface WindowCounter {
        long increment(String bucketKey, long windowMillis);
    }

    private final long windowMillis;
    private final WindowCounter counter;
    private final RateLimitStore fallback;
    private final AutoCloseable[] resources;

    public RedisRateLimitStore(long windowMillis, WindowCounter counter,
                               RateLimitStore fallback, AutoCloseable... resources) {
        this.windowMillis = Math.max(1, windowMillis);
        this.counter = counter;
        this.fallback = fallback;
        this.resources = resources;
    }

    @Override
    public long retryAfterSeconds(String key, int max, long nowMillis) {
        long bucket = nowMillis / windowMillis;
        // {key} hash-tag keeps the bucket on one slot under Redis Cluster.
        String bucketKey = "embedrl:{" + key + "}:" + bucket;
        long count;
        try {
            count = counter.increment(bucketKey, windowMillis);
        } catch (RuntimeException e) {
            log.warn("Rate-limit Redis call failed; degrading to in-memory for this hit: {}", e.toString());
            return fallback.retryAfterSeconds(key, max, nowMillis);
        }
        if (count <= max) {
            return -1;
        }
        long resetInMillis = (bucket + 1) * windowMillis - nowMillis;
        return Math.max(1, (resetInMillis + 999) / 1000); // ceil to seconds, min 1
    }

    @Override
    public void close() {
        for (AutoCloseable r : resources) {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Exception e) {
                log.debug("Error closing rate-limit Redis resource: {}", e.toString());
            }
        }
    }
}
