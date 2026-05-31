# Luke Core Engine

Multi-tenant [CIBSeven](https://www.cibseven.org/) (Camunda 7) process engine. Spring Boot 3.4 + Java 21 + PostgreSQL. Customer organizations map to Camunda tenants; an admin "parent cluster" tenant exists for cluster operators.

## What's in this repo

- `src/main/java/com/luke/engine/` — Spring Boot app wrapping the CIBSeven BPMN engine, custom tenant filters, REST controllers
- `src/main/resources/application*.yml` — Configuration (H2 default, Postgres via `postgres` profile)
- `Dockerfile` — Multi-stage container build (JDK 21 build → JRE runtime)
- `render.yaml` — [Render](https://render.com) Blueprint for one-click deploy (web service + managed Postgres)

## Local development

### Prerequisites

- Java 21 (`brew install openjdk@21`)
- Maven via included wrapper (`./mvnw`)

### Run with H2 (default, no setup)

```bash
./mvnw spring-boot:run
```

Engine boots on `http://localhost:8080`. H2 file store lives at `./luke-data/` (gitignored).

### Run with PostgreSQL (matches prod)

```bash
SPRING_PROFILES_ACTIVE=postgres \
DB_URL=jdbc:postgresql://localhost:5432/luke_camunda \
DB_USERNAME=luke DB_PASSWORD=luke \
./mvnw spring-boot:run
```

### Wipe and restart (dev DB reset)

```bash
lsof -ti tcp:8080 | xargs kill 2>/dev/null
rm -rf luke-data/
./mvnw spring-boot:run
```

### First boot

On a fresh database, the engine seeds:
- `admin` user (password from `CAMUNDA_ADMIN_PASSWORD`, default `admin`)
- `camunda-admin` group with full authorization grants; admin is a member
- `parent_cluster` tenant (the operations tenant); admin is a member

## Deploy to Render

Push to a GitHub repo, then on Render: **Dashboard → New → Blueprint → connect repo**. `render.yaml` provisions:
- Web service (Starter $7/mo, Ohio region) running the Dockerfile
- Managed PostgreSQL (Starter $7/mo, PostgreSQL 16, same region)

First deploy takes ~5-8 min. Render auto-generates a random `CAMUNDA_ADMIN_PASSWORD` — retrieve it from the service's **Environment** tab.

After deploy:
```bash
curl https://<your-service>.onrender.com/actuator/health
curl -u admin:<password> https://<your-service>.onrender.com/engine-rest/tenant
# → [{"id":"parent_cluster","name":"Parent Cluster"}]
```

## Configuration

| Env var | Default | Description |
|---|---|---|
| `PORT` | `8080` | HTTP listen port (Render injects this) |
| `SPRING_PROFILES_ACTIVE` | — | Set to `postgres` for Postgres profile |
| `DB_URL` | — | JDBC URL override (else built from POSTGRES_* fields) |
| `POSTGRES_HOST` | `localhost` | Postgres host (Render auto-injects from blueprint) |
| `POSTGRES_PORT` | `5432` | Postgres port |
| `POSTGRES_DATABASE` | `luke_camunda` | Database name |
| `DB_USERNAME` | `luke` | Postgres username |
| `DB_PASSWORD` | `luke` | Postgres password |
| `CAMUNDA_ADMIN_USER` | `admin` | Bootstrap admin user |
| `CAMUNDA_ADMIN_PASSWORD` | `admin` | Bootstrap admin password — **override in prod** |
| `LUKE_PARENT_CLUSTER_ID` | `parent_cluster` | Tenant ID for ops/cluster manager tenant |
| `LUKE_PARENT_CLUSTER_NAME` | `Parent Cluster` | Display name for ops tenant |
| `ALLOWED_ORIGINS` | `http://localhost:*` | Comma-separated CORS origin patterns |

## Architecture

### Multi-tenancy
Every `/engine-rest/*` request requires a tenant scope via the `X-Tenant-Id` header. Identity and tenant-management endpoints (`/tenant`, `/user`, `/group`, `/identity`) are exempt — see [TenantFilter](src/main/java/com/luke/engine/config/TenantFilter.java).

### Parent cluster tenant
`parent_cluster` is the operations tenant used by cluster managers (admin/admin via the operator console UI). It is **not** a default tenant for end-user signups. Customer organizations create their own tenants through the consumer-facing app's signup flow:

```http
POST /engine-rest/user/create
POST /engine-rest/tenant/create
PUT  /engine-rest/tenant/{tenantId}/user-members/{userId}
POST /engine-rest/authorization/create    # tenant-scoped grants
```

### Authorization
Camunda authorization is **enabled**. The `camunda-admin` group (auto-created on first boot; admin is a member) has full grants. Non-admin users must receive explicit tenant-scoped authorizations on signup or they see a blank app.

[RestApiAuthFilter](src/main/java/com/luke/engine/config/RestApiAuthFilter.java) binds the user's group memberships into Camunda's authentication context, so grants inherited via `camunda-admin` are honored.

### BPMN deployment
No BPMN process definitions are auto-deployed on startup. Customer-specific BPMNs are deployed per-tenant on-demand via REST (`POST /engine-rest/deployment/create`) or via the signup flow.

## API endpoints

| Path | Purpose |
|---|---|
| `/engine-rest/**` | Standard Camunda REST API (process definitions, instances, tasks, identity) |
| `/api/topics/**` | External task topic registry CRUD ([TopicRegistryController](src/main/java/com/luke/engine/topic/TopicRegistryController.java)) |
| `/actuator/health` | Spring Boot health endpoint |
| `/actuator/metrics` | Spring Boot metrics |

## Common operations

### List deployments under parent_cluster
```bash
curl -u admin:<pwd> 'http://localhost:8080/engine-rest/deployment?tenantIdIn=parent_cluster' \
  -H 'X-Tenant-Id: parent_cluster'
```

### Delete a deployment (cascade — removes process defs, instances, history)
```bash
curl -u admin:<pwd> -X DELETE \
  'http://localhost:8080/engine-rest/deployment/{deploymentId}?cascade=true' \
  -H 'X-Tenant-Id: parent_cluster'
```

### Test the Docker build locally
```bash
docker build -t luke-core-engine .
docker run --rm -p 8080:8080 -e CAMUNDA_ADMIN_PASSWORD=test luke-core-engine
```
