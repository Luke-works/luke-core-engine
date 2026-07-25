package com.luke.engine.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.luke.engine.web.RedisRateLimitStore.WindowCounter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.embedded.RedisServer;

/**
 * #55: proves the shared embed limiter against a REAL Redis (embedded — no Docker), including the
 * whole point — two SEPARATE store instances (standing in for two core-engine replicas) share one
 * global limit, so the public embed surface can't be hammered harder by scaling out. Skips
 * gracefully where embedded Redis can't start; runs in CI (linux/x64).
 */
class RedisRateLimitIntegrationTest {

    private static final int PORT = 16400;
    private static final long WINDOW = 60_000L;
    private static final String INCR_EXPIRE =
            "local c = redis.call('INCR', KEYS[1]) "
            + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
            + "return c";

    private static RedisServer server;

    @BeforeAll
    static void startRedis() {
        try {
            server = new RedisServer(PORT);
            server.start();
        } catch (Exception e) {
            server = null; // embedded redis unavailable on this platform → tests skip
        }
    }

    @AfterAll
    static void stopRedis() {
        if (server != null) {
            try { server.stop(); } catch (Exception ignore) { /* best effort */ }
        }
    }

    private WindowCounter counterOn(RedisCommands<String, String> sync) {
        return (bucketKey, wm) ->
                (Long) sync.eval(INCR_EXPIRE, ScriptOutputType.INTEGER, new String[] {bucketKey}, String.valueOf(wm));
    }

    @Test
    void realRedisEnforcesTheLimitAndSharesItAcrossReplicas() {
        assumeTrue(server != null, "embedded Redis not available on this platform");

        RedisClient client = RedisClient.create("redis://localhost:" + PORT);
        RateLimitStore fallback = new InMemoryRateLimitStore(100);
        // Two independent store instances → two "replicas" pointing at the same Redis.
        try (StatefulRedisConnection<String, String> connA = client.connect();
             StatefulRedisConnection<String, String> connB = client.connect();
             RedisRateLimitStore replicaA = new RedisRateLimitStore(WINDOW, counterOn(connA.sync()), fallback);
             RedisRateLimitStore replicaB = new RedisRateLimitStore(WINDOW, counterOn(connB.sync()), fallback)) {

            long now = 1_700_000_000_000L;
            String key = "itest|embed-render-t:tok";
            int max = 2;

            assertEquals(-1, replicaA.retryAfterSeconds(key, max, now), "1st hit (on A) allowed");
            assertEquals(-1, replicaB.retryAfterSeconds(key, max, now), "2nd hit (on B) allowed — shared count = 2");
            // The 3rd hit is over the GLOBAL max of 2, regardless of which replica serves it.
            assertTrue(replicaA.retryAfterSeconds(key, max, now) >= 1, "3rd hit limited on A (shared)");
            assertTrue(replicaB.retryAfterSeconds(key, max, now) >= 1, "and limited on B (shared)");

            // A different key is independent.
            assertEquals(-1, replicaA.retryAfterSeconds("itest|embed-render-t:other", max, now));
        } finally {
            client.shutdown();
        }
    }
}
