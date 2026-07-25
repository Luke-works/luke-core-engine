package com.luke.engine.config;

import com.luke.engine.web.InMemoryRateLimitStore;
import com.luke.engine.web.RateLimitStore;
import com.luke.engine.web.RedisRateLimitStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Chooses the backing store for the public-embed rate limiter (#55): a shared Redis limiter when
 * {@code REDIS_URL} is configured, else the in-memory per-instance {@link InMemoryRateLimitStore}.
 *
 * <p><b>Default-lenient:</b> with no {@code REDIS_URL} (dev/qa, and prod until Redis is provisioned)
 * the in-memory limiter is used — the shipped behaviour is unchanged. If {@code REDIS_URL} is set
 * but Redis is unreachable at startup, the app still boots on the in-memory limiter (logged loudly)
 * rather than failing — a rate limiter must never take the engine down.
 */
@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    /** Same 1-minute window as {@link InMemoryRateLimitStore}, so behaviour matches with or without Redis. */
    private static final long WINDOW_MILLIS = 60_000L;

    /** Atomic INCR + first-hit PEXPIRE, so a counted window always has a TTL (no leak). */
    private static final String INCR_EXPIRE =
            "local c = redis.call('INCR', KEYS[1]) "
            + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
            + "return c";

    @Bean
    RateLimitStore rateLimitStore(@Value("${REDIS_URL:}") String redisUrl,
                                  @Value("${luke.ratelimit.max-keys:50000}") int maxKeys) {
        InMemoryRateLimitStore inMemory = new InMemoryRateLimitStore(maxKeys);

        if (!StringUtils.hasText(redisUrl)) {
            log.info("Embed rate limiter: in-memory (per-instance). Set REDIS_URL for a global "
                    + "cross-replica limit under horizontal scale-out.");
            return inMemory;
        }

        RedisClient client = null;
        try {
            client = RedisClient.create(redisUrl);
            StatefulRedisConnection<String, String> conn = client.connect(); // validates connectivity
            RedisCommands<String, String> sync = conn.sync();
            RedisRateLimitStore.WindowCounter counter = (bucketKey, wm) ->
                    (Long) sync.eval(INCR_EXPIRE, ScriptOutputType.INTEGER,
                            new String[] {bucketKey}, String.valueOf(wm));
            RedisClient created = client;
            log.info("Embed rate limiter: Redis-backed GLOBAL limiter via REDIS_URL.");
            return new RedisRateLimitStore(WINDOW_MILLIS, counter, inMemory, conn, created::shutdown);
        } catch (Exception e) {
            log.error("REDIS_URL is set but Redis is unavailable at startup — falling back to the "
                    + "in-memory (per-instance) limiter. The app still serves; fix Redis to restore "
                    + "the global limit.", e);
            if (client != null) {
                try { client.shutdown(); } catch (Exception ignore) { /* best effort */ }
            }
            return inMemory;
        }
    }
}
