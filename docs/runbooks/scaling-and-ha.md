# Runbook: Scaling & high availability

Current capacity posture and the path to multi-instance HA.

## Today (single instance)

- One Render web service (`starter`, 512 MB) + one Postgres (`basic-256mb`, 1 GB).
- **Not horizontally scalable yet** — see the HA blockers below.
- Vertical headroom first: bump the web plan if memory-bound (#22), bump the DB plan if
  storage/connection-bound. Hikari pool is sized to 8 (`application-postgres.yml`) for the
  small DB — raise it in step with the DB plan, not ahead of it.

## HA blockers (must clear before running >1 instance)

| Blocker | Issue | Why it breaks multi-instance |
|---------|-------|------------------------------|
| Uncoordinated boot initializers/backfills | #40 | Each instance runs seeders/backfills on boot → races/dupes |
| In-memory per-instance rate limiters | #55 (embed), agents #27 | Limits apply per instance, not globally |
| Tenant context correctness on pooled/async threads | #21 | Must hold under concurrency across instances |

Camunda's job executor IS cluster-safe (jobs are locked per-row in the DB), so multiple
engines can share the one database for job execution once the above are resolved.

## Path to multi-instance

1. Make boot initializers idempotent + leader-gated or migration-driven (#40).
2. Move rate-limit state to a shared store (e.g. Postgres/Redis) (#55, agents #27).
3. Confirm graceful shutdown drains correctly under load (#44, done) so rolling deploys
   don't drop work.
4. Raise web service instance count; keep the shared Postgres (scale the DB plan to match
   the added job-executor + request concurrency).
5. Load-test: verify no duplicate seeding, correct per-tenant isolation, and that the
   shared job executor distributes work without double-execution.

## Capacity signals to watch (until #22 lands alerting)

- Web memory > ~80% sustained → bump plan or investigate leak/large payloads.
- DB storage > ~70% of 1 GB → confirm history cleanup (#29) and/or bump plan.
- DB connections near pool max → raise pool + DB plan together.
