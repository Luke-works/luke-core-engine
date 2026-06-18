package com.luke.engine.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables {@code @Scheduled} (the form-submission outbox consumer drains the queue)
 * and {@code @Async} (off-request-thread work such as process-triggered email).
 *
 * <p>Without {@code @EnableScheduling}/{@code @EnableAsync} those annotations are
 * SILENTLY ignored — the outbox would never drain and {@code @Async} would run on
 * the caller's thread. This config is the single place both are turned on.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class AsyncSchedulingConfig {

    /**
     * Bounded executor backing {@code @Async}. Caller-runs when saturated so work is
     * throttled rather than dropped. Named {@code applicationTaskExecutor} so it is
     * the default {@code @Async} executor (Spring Boot backs off its own).
     */
    @Bean(name = "applicationTaskExecutor")
    public Executor applicationTaskExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("luke-async-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }
}
