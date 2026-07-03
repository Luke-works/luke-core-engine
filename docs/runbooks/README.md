# luke-core-engine — Operational Runbooks

Disaster-recovery and operational procedures for the core engine (#36).

| Runbook | When to use |
|---------|-------------|
| [disaster-recovery.md](disaster-recovery.md) | Database lost/corrupted; restore from backup |
| [bad-deploy-rollback.md](bad-deploy-rollback.md) | A deploy broke prod/qa; roll back |
| [incident-triage.md](incident-triage.md) | Service degraded/erroring; first response |
| [scaling-and-ha.md](scaling-and-ha.md) | Capacity planning; path to multi-instance |

## Service topology

- **Runtime:** single Render web service (`luke-core-engine`), Docker, JVM as PID 1.
- **Datastore:** one Render managed Postgres (`luke-camunda-db`, `basic-256mb`, 1 GB).
  Several environments (dev/qa/uat) may share one instance, isolated per schema via
  `DB_SCHEMA` (see `application-postgres.yml`).
- **Schema:** Camunda `ACT_*` tables are engine-managed (`schema-update`); custom
  `luke_*` tables are **Flyway-managed** (`db/migration/`, see #24). `ddl-auto: none`
  in prod — no implicit schema mutation.
- **Deploys:** `main` → prod (manual), `develop` → dev/qa (auto). Graceful shutdown
  drains in-flight requests/jobs on SIGTERM (#44).
- **Health:** liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness`
  (DB-aware; flips DOWN during drain), aggregate `/actuator/health`.

## Recovery objectives (proposed — confirm with the business)

| Objective | Target | Basis |
|-----------|--------|-------|
| **RPO** (max data loss) | ≤ 24h | Render daily automated backup; tighten with PITR on a higher plan |
| **RTO** (max downtime) | ≤ 2h | Restore-to-new-instance + repoint, rehearsed |

> ⚠️ **Verify in the Render dashboard:** backup cadence/retention and PITR availability
> depend on the Postgres plan. `basic-256mb` provides daily backups; PITR requires a
> larger plan. Confirm the actual settings and update the table above. A restore has
> **not yet been rehearsed** — schedule one (see disaster-recovery.md) and record the
> measured RTO.
