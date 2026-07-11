package com.luke.engine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Guards against JPA-entity ↔ Flyway-migration <em>drift</em> — the class of bug that H2 tests
 * structurally cannot catch.
 *
 * <p>Every other test runs on H2 with {@code ddl-auto=create}, so Hibernate builds the schema FROM
 * the entities — entity and schema can never disagree there. Production runs on PostgreSQL with
 * {@code ddl-auto=none}, where the Flyway migrations are the source of truth; if an entity's mapped
 * column name/type doesn't match what a migration created, every query against it 500s at runtime —
 * exactly the {@code fieldH → "fieldh"} vs migration {@code field_h} bug the pentest surfaced, which
 * broke e-signatures on all Postgres environments while every H2 test stayed green.
 *
 * <p>This boots the real application on a throwaway PostgreSQL (Testcontainers) exactly as prod does
 * — the {@code postgres} profile, the real Flyway migrations applied — but with
 * {@code ddl-auto=validate}: Hibernate checks every {@code @Entity} against the migrated schema at
 * startup. Any drift throws a {@code SchemaManagementException} and fails context load (and thus this
 * test) before the body runs. Automatically skipped where Docker is unavailable, so it never blocks a
 * local {@code mvn test} on a machine without Docker; CI (which has Docker) always runs it.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class PostgresSchemaValidationTest {

    @Container
    @SuppressWarnings("resource") // Testcontainers manages the lifecycle for a static @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // The postgres profile keeps Flyway as the schema source of truth (ddl-auto: none). Here we
        // still let Flyway build the schema, but flip Hibernate to VALIDATE so it asserts every entity
        // mapping against what the migrations actually created — the whole point of this test.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Fresh container's default schema is 'public'; keep everything there (no per-env isolation).
        registry.add("DB_SCHEMA", () -> "public");
        // Satisfy the postgres-profile fail-fast guards so the context boots far enough to validate:
        // AdminPasswordGuard requires a non-default Camunda admin password; H2ConsoleGuard requires the
        // H2 console off. (The prod-only guards — InsecureKey/AuthHardening/Edge — don't fire here.)
        registry.add("fluxnova.bpm.admin-user.password", () -> "test-Str0ng-Passw0rd-not-default");
        registry.add("spring.h2.console.enabled", () -> "false");
    }

    @Test
    void entitiesMatchTheFlywayMigratedPostgresSchema() {
        // Intentionally empty. If the context loaded, Flyway migrated a real PostgreSQL and Hibernate
        // validated every @Entity against it — a passing test means no entity/migration drift.
    }
}
