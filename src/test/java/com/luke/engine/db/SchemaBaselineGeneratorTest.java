package com.luke.engine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;

/**
 * Regenerates the canonical PostgreSQL DDL for the custom {@code luke_*} tables
 * straight from the JPA metadata, using the SAME naming strategies Spring Boot
 * applies at runtime ({@link CamelCaseToUnderscoresNamingStrategy} physical +
 * {@link SpringImplicitNamingStrategy} implicit) and the Postgres dialect.
 *
 * <p>This is how the Flyway baseline (V1) was produced and how it stays honest:
 * run {@code ./mvnw -Dtest=SchemaBaselineGeneratorTest test} and diff
 * {@code target/generated-luke-schema.sql} against the committed
 * {@code db/migration/V1__baseline_luke_tables.sql} when entities change. It does
 * NOT touch a database or boot Camunda — pure script generation — so it is safe to
 * run anywhere. Camunda's ACT_* tables are intentionally excluded (engine-managed).
 */
class SchemaBaselineGeneratorTest {

    /** All custom @Entity classes (Camunda ACT_* tables are NOT JPA entities). */
    private static final Class<?>[] ENTITIES = {
        com.luke.engine.topic.RegisteredTopic.class,
        com.luke.engine.capability.form.FormDefinition.class,
        com.luke.engine.capability.form.FormVersion.class,
        com.luke.engine.capability.form.FormInstance.class,
        com.luke.engine.capability.form.FormAuditEvent.class,
        com.luke.engine.capability.form.FormSubmissionOutbox.class,
        com.luke.engine.capability.email.EmailServer.class,
        com.luke.engine.capability.email.EmailMessage.class,
        com.luke.engine.capability.email.EmailVerification.class,
        com.luke.engine.capability.emailtemplate.EmailTemplate.class,
        com.luke.engine.capability.emailtemplate.EmailTemplateVersion.class,
        com.luke.engine.capability.emailtemplate.EmailTemplateAuditEvent.class,
        com.luke.engine.capability.capability.Capability.class,
        com.luke.engine.capability.capability.CapabilitySubscription.class,
        com.luke.engine.capability.access.CapabilityGrant.class,
        com.luke.engine.capability.access.AccessRequest.class,
        com.luke.engine.capability.secrets.Secret.class,
        // Signatures capability (merged from luke-signature-engine, SIG-M) → V6 migration.
        com.luke.engine.capability.signature.SignatureDefinition.class,
        com.luke.engine.capability.signature.SignatureVersion.class,
        com.luke.engine.capability.signature.SignatureDefinitionAuditEvent.class,
        com.luke.engine.capability.signature.SignatureInstance.class,
        com.luke.engine.capability.signature.SignatureRecipient.class,
        com.luke.engine.capability.signature.SignatureProcessOutbox.class,
        com.luke.engine.capability.signature.SignatureRequest.class,
        com.luke.engine.capability.signature.SignatureAuditEvent.class,
        // Phone / Voice (Vapi) capability → V8 migration.
        com.luke.engine.capability.phone.PhoneCall.class,
        com.luke.engine.capability.phone.PhoneNumber.class,
        com.luke.engine.capability.phone.PhoneSettings.class,
        com.luke.engine.capability.phone.PhoneCallProcessOutbox.class,
        // Workflow capability + integrations (Nango) module → V9 migration.
        com.luke.engine.workflow.WorkflowDefinition.class,
        com.luke.engine.workflow.WorkflowVersion.class,
        com.luke.engine.workflow.integrations.IntegrationConnection.class,
        com.luke.engine.workflow.integrations.IntegrationWebhookLog.class,
        com.luke.engine.workflow.integrations.IntegrationEventOutbox.class,
        com.luke.engine.workflow.integrations.IntegrationUsageEvent.class,
    };

    @Test
    void generatePostgresBaseline() throws Exception {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                // Offline generation: pin the dialect and never open a JDBC connection
                // for metadata defaults (no database is available here).
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            for (Class<?> e : ENTITIES) {
                sources.addAnnotatedClass(e);
            }
            Metadata metadata = sources.getMetadataBuilder()
                    .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                    .applyImplicitNamingStrategy(new SpringImplicitNamingStrategy())
                    .build();

            File out = new File("target/generated-luke-schema.sql");
            out.getParentFile().mkdirs();
            if (out.exists()) {
                out.delete();
            }

            Map<String, Object> settings = new HashMap<>();
            settings.put("jakarta.persistence.schema-generation.scripts.action", "create");
            settings.put("jakarta.persistence.schema-generation.scripts.create-target", out.getPath());
            settings.put("hibernate.hbm2ddl.delimiter", ";");
            settings.put("hibernate.format_sql", "true");
            // SchemaManagementToolCoordinator drives JPA script generation from the
            // metadata + dialect; the no-op DelayedDropRegistry is fine (CREATE only).
            SchemaManagementToolCoordinator.process(metadata, registry, settings, action -> {});

            String ddl = Files.readString(out.toPath());

            // Guard: every table the entities produce must be present in the committed
            // Flyway migrations. If someone adds an @Entity without adding it here AND to a
            // migration, this fails — preventing the silent "table never created" gap.
            // Read ALL migrations (V1 baseline + later adds like V6 signatures), not just V1.
            File migrationsDir = new File("src/main/resources/db/migration");
            StringBuilder baselineSb = new StringBuilder();
            for (File f : migrationsDir.listFiles((d, n) -> n.endsWith(".sql"))) {
                baselineSb.append(Files.readString(f.toPath())).append('\n');
            }
            String baseline = baselineSb.toString();
            Matcher m = Pattern.compile("create table (?:if not exists )?(\\w+)").matcher(ddl);
            int tables = 0;
            while (m.find()) {
                String table = m.group(1);
                tables++;
                assertTrue(baseline.contains(table),
                        "Entity table '" + table + "' is missing from the V1 Flyway baseline — "
                        + "add a migration (regenerate with SchemaBaselineGeneratorTest).");
            }
            assertEquals(ENTITIES.length, tables,
                    "Expected one table per @Entity in the generated schema");
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void postgresProfileHandsSchemaToFlyway() throws Exception {
        // The whole point of #24: in Postgres, Hibernate must not auto-mutate the schema
        // and Flyway must be enabled with a safe baseline for existing environments.
        String pg = Files.readString(
                new File("src/main/resources/application-postgres.yml").toPath());
        assertTrue(pg.contains("ddl-auto: none"), "postgres profile must set ddl-auto: none");
        assertTrue(pg.contains("enabled: true"), "Flyway must be enabled in the postgres profile");
        assertTrue(pg.contains("baseline-on-migrate: true"),
                "baseline-on-migrate must be true so existing schemas aren't recreated");

        // ...and Flyway must stay OFF in the default/H2 profile (Postgres DDL would fail on H2).
        String def = Files.readString(new File("src/main/resources/application.yml").toPath());
        assertTrue(def.contains("enabled: false"), "Flyway must be disabled in the default profile");
    }
}
