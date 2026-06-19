package com.luke.engine.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-closed guard against exposing the H2 web console in a deployed environment
 * (#34). The console is an UNAUTHENTICATED SQL shell — Spring Security permits all
 * requests and the engine's auth filters only cover {@code /engine-rest} + {@code /api},
 * so {@code /h2-console} would be wide open.
 *
 * <p>The console now defaults to OFF (it's a local-dev convenience, opt in via
 * {@code H2_CONSOLE_ENABLED=true}). As defense-in-depth, this guard refuses to start
 * if it is enabled while the prod profile ({@code postgres}) is active, so it can
 * never be accidentally turned on in dev/qa or prod.
 */
@Component
public class H2ConsoleGuard {

    private static final Logger log = LoggerFactory.getLogger(H2ConsoleGuard.class);
    private static final String ENFORCED_PROFILE = "postgres";

    private final Environment environment;
    private final boolean consoleEnabled;

    public H2ConsoleGuard(Environment environment,
                          @Value("${spring.h2.console.enabled:false}") boolean consoleEnabled) {
        this.environment = environment;
        this.consoleEnabled = consoleEnabled;
    }

    @PostConstruct
    void verify() {
        if (!consoleEnabled) {
            return;
        }
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (activeProfiles.contains(ENFORCED_PROFILE)) {
            throw new IllegalStateException(
                    "Refusing to start: the H2 console is enabled while the '" + ENFORCED_PROFILE
                    + "' profile is active — it is an unauthenticated SQL shell and must never be "
                    + "exposed in a deployed environment. Unset H2_CONSOLE_ENABLED.");
        }
        log.warn("H2 console is ENABLED (unauthenticated SQL shell at /h2-console) — local dev only. "
                + "Never enable it in a shared/prod environment.");
    }
}
