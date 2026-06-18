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
 * Fail-closed guard against shipping the Camunda admin account with a blank or
 * default password.
 *
 * <p>{@code application.yml} defaults {@code camunda.bpm.admin-user.password} to
 * {@code "admin"} for local dev. If {@code CAMUNDA_ADMIN_PASSWORD} is left unset
 * in a real deployment, that default would create a cross-tenant super-user with
 * a guessable password (GHSA-8cj8-q9jr-h2x9 / #38).
 *
 * <p>This guard refuses to start the application when the prod profile
 * ({@code postgres}) is active and the resolved admin password is blank or equal
 * to the insecure default. In local/dev (default profile) it only warns, so the
 * convenient {@code admin/admin} login still works on H2.
 */
@Component
public class AdminPasswordGuard {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordGuard.class);
    private static final String INSECURE_DEFAULT = "admin";
    /** Profiles in which a blank/default admin password is a hard failure. */
    private static final String ENFORCED_PROFILE = "postgres";

    private final Environment environment;
    private final String adminUser;
    private final String adminPassword;

    public AdminPasswordGuard(Environment environment,
                              @Value("${camunda.bpm.admin-user.id:admin}") String adminUser,
                              @Value("${camunda.bpm.admin-user.password:}") String adminPassword) {
        this.environment = environment;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
    }

    @PostConstruct
    void verify() {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        boolean enforced = activeProfiles.contains(ENFORCED_PROFILE);
        boolean insecure = adminPassword == null || adminPassword.isBlank()
                || INSECURE_DEFAULT.equals(adminPassword);

        if (!insecure) {
            return;
        }
        if (enforced) {
            throw new IllegalStateException(
                    "Refusing to start: the Camunda admin account '" + adminUser
                    + "' is using a blank or default password. Set CAMUNDA_ADMIN_PASSWORD to a strong,"
                    + " non-default value before deploying (active profiles: " + activeProfiles + ").");
        }
        log.warn("Camunda admin '{}' is using the INSECURE default password — acceptable for local dev only. "
                + "Set CAMUNDA_ADMIN_PASSWORD before deploying.", adminUser);
    }
}
