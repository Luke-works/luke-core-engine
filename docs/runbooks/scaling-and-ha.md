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
- DB storage > ~70% of current size → **expand storage** (history is retained by policy,
  not purged — #29). See the retention/storage note below.
- DB connections near pool max → raise pool + DB plan together.

## History retention & storage (#29)

**Policy: retain full process + form-submission history; do NOT purge it.** It has
audit/compliance value, so the engine runs with `history-level: full` and **no** history
cleanup window or TTL (see `application.yml` and `HistoryRetentionConfigTest`). History
(`ACT_HI_*`) therefore grows monotonically by design — the answer to a filling disk is
**more storage, not deletion**.

How to expand storage:
1. **Render dashboard → `luke-camunda-db` → expand storage** (Render Postgres storage can
   be increased; it cannot be shrunk — size up in steps, don't over-provision at once).
   If a larger compute plan is also needed (CPU/RAM/connections), bump the `plan` in
   `render.yaml` in the same change.
2. Re-check the storage trend after growth settles; set the next watch threshold relative
   to the new size.

Operational guidance:
- **Watch the growth rate**, not just the absolute %: estimate `GB/month` from the storage
  metric and keep ≥ ~3 months of headroom so an expansion is never urgent.
- If cost of hot Postgres storage becomes the concern, the correct lever is **archival,
  not deletion**: periodically export cold `ACT_HI_*` partitions to cheaper object storage
  (and keep them queryable out-of-band) rather than enabling Camunda cleanup. Only if a
  hard regulatory retention *limit* is ever mandated should a cleanup window + TTL be
  introduced (and that decision recorded on #29).

