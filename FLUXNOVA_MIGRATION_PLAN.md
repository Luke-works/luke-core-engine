# CIBSeven → FluxNova Migration Plan (luke-core-engine)

Migrate the embedded BPM engine from **CIBSeven `2.1.0`** (`org.cibseven.*`) to
**FluxNova `2.0.3`** (`org.finos.fluxnova.*`), the FINOS / Linux Foundation
Apache-2.0 fork of Camunda 7.

Branch: `migrate/cibseven-to-fluxnova` (off `develop`).

## Why this is low-risk (verified against the actual 2.0.3 JARs on Maven Central)

| Concern | Finding | Impact |
|---|---|---|
| Java version | Already on Java 21 (`pom.xml` `<java.version>21</java.version>`) | FluxNova's hard requirement already met |
| Java package root | FluxNova ships **only** `org.finos.fluxnova.*` (3306 classes, zero `org.camunda` leakage) | Import rename is 1:1 mechanical: `org.cibseven.` → `org.finos.fluxnova.` |
| Artifact coordinates | All 6 deps exist on Maven Central at `2.0.3` with the same names, `cibseven-`→`fluxnova-` | pom rewrite is 1:1 |
| **Spring Boot config prefix** | **RENAMED** `camunda.bpm.*` → `fluxnova.bpm.*` (CIBSeven kept `camunda.bpm.*`) | **Must** rewrite `application.yml`, `application-postgres.yml`, and 5 `@Value` sites — the one non-mechanical part |
| BPMN model namespace | `http://camunda.org/schema/1.0/bpmn` still recognized by `BpmnParse` | Existing BPMN, `camunda:connector` elements, `TopicValidationParseListener` unchanged |
| Engine schema (ACT_*) | Both are Camunda-7.x forks; DB strategy = **fresh schemas on dev first** | Prove the swap on empty DB; decide prod data path afterward |

## Coordinate mapping (pom.xml)

| CIBSeven (`2.1.0`) | FluxNova (`2.0.3`) |
|---|---|
| `org.cibseven.bpm:cibseven-bom` | `org.finos.fluxnova.bpm:fluxnova-bom` |
| `org.cibseven.bpm.springboot:cibseven-bpm-spring-boot-starter-rest` | `org.finos.fluxnova.bpm.springboot:fluxnova-bpm-spring-boot-starter-rest` |
| `org.cibseven.bpm:cibseven-engine-plugin-spin` | `org.finos.fluxnova.bpm:fluxnova-engine-plugin-spin` |
| `org.cibseven.spin:cibseven-spin-dataformat-json-jackson` | `org.finos.fluxnova.spin:fluxnova-spin-dataformat-json-jackson` |
| `org.cibseven.bpm:cibseven-engine-plugin-connect` | `org.finos.fluxnova.bpm:fluxnova-engine-plugin-connect` |
| `org.cibseven.connect:cibseven-connect-connectors-all` | `org.finos.fluxnova.connect:fluxnova-connect-connectors-all` |

## Steps

1. **pom.xml** — rename version property `cibseven.version`→`fluxnova.version` (`2.0.3`),
   BOM + 6 dependency coordinates, and the project `<description>`.
2. **Java imports** — mechanical rename `org.cibseven.` → `org.finos.fluxnova.`
   across all 63 Java files (229 imports; includes `SpinValues`,
   `AbstractProcessEnginePlugin`, `AbstractBpmnParseListener`, engine/identity/
   runtime/task/model.bpmn service classes).
3. **Config prefix** — `application.yml` and `application-postgres.yml`:
   root key `camunda:` → `fluxnova:`. The `${CAMUNDA_ADMIN_USER}` /
   `${CAMUNDA_ADMIN_PASSWORD}` **env-var placeholders stay as-is** (they are just
   env names referenced by render.yaml — decoupled from the config key).
4. **@Value sites** — 5 functional injections `${camunda.bpm.admin-user.*}` →
   `${fluxnova.bpm.admin-user.*}` (TenantOwnerBackfill, AdminPasswordGuard ×2,
   ParentClusterInitializer, OrganizationController, AdminTenantMembershipBackfill)
   + the AdminPasswordGuard javadoc reference.
5. **Logging level** — `org.cibseven: INFO` → `org.finos.fluxnova: INFO`.
6. **Cosmetic** — `CamundaConfig` boot log + config comment headers say "CIBSeven"
   → "FluxNova".
7. **Build gate** — `./mvnw -q -DskipTests package` then `./mvnw test`
   (H2 + Testcontainers; no external DB needed). Must be green.

## Preserved intentionally (do NOT change)

- The `camunda:connector` / `camunda:` BPMN element namespace in models and
  `TopicValidationParseListener` (`http://camunda.org/schema/1.0/bpmn`).
- The `camunda-admin` identity **group name** (engine authorization group; not a
  config key — renaming it would break authorization checks and existing data).
- `camundaTaskId` variable name, `luke_camunda` DB name, `CAMUNDA_ADMIN_*` env vars.
- Flyway `luke_*` migrations + `beforeMigrate.sql` (engine still owns ACT_* via
  `fluxnova.bpm.database.schema-update`).

## Database rollout (chosen: fresh DBs on dev first)

- **Dev/qa:** point FluxNova at empty schemas; `schema-update: true` +
  `schema-name`/`table-prefix` create ACT_* cleanly per env. Validate isolation.
- **Prod:** decide after dev is green. Because both are Camunda-7.x forks the ACT_*
  DDL is compatible in practice, but the engine writes a schema-version row — clone
  prod, boot FluxNova against the clone, and confirm no schema-version mismatch
  before touching real prod data. (Tracked as a follow-up, not in this branch.)

## Rollback

Single-branch revert: `git checkout develop`. No DB writes happen until FluxNova
boots against a database, and dev uses throwaway schemas.
