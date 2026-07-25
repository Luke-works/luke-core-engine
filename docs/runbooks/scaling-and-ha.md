# Runbook: Scaling & high availability

Current capacity posture and the path to multi-instance HA.

## Today (single instance)

- One Render web service (`starter`, 512 MB) + one Postgres (`basic-256mb`, 1 GB).
- **Not horizontally scalable yet** — see the HA blockers below.
- Vertical headroom first: bump the web plan if memory-bound (#22), bump the DB plan if
  storage/connection-bound. Hikari pool is sized to 8 (`application-postgres.yml`) for the
  small DB — raise it in step with the DB plan, not ahead of it.

## HA blockers (must clear before running >1 instance)

| Blocker | Issue | Status |
|---------|-------|--------|
| Uncoordinated boot initializers/backfills | #40 | ✅ Resolved — each boot writer runs under a `BootCoordinator` Postgres advisory lock (serialized across instances; no-op on H2). |
| In-memory per-instance rate limiters | #55 (embed), agents #27 | ⬜ Open — limits apply per instance, not globally |
| Tenant context correctness on pooled/async threads | #21 | ⬜ Open — must hold under concurrency across instances |

Camunda's job executor IS cluster-safe (jobs are locked per-row in the DB), so multiple
engines can share the one database for job execution once the above are resolved.

## Path to multi-instance

1. ~~Make boot initializers safe across instances~~ — done (#40, advisory-lock serialized).
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


## Job executor & horizontal-scaling model (#28)

The engine runs as **N identical instances of the same application** against **one shared
Postgres**. Every instance runs the same process definitions, so any instance can execute any
due job.

**Sizing (`fluxnova.bpm.job-execution` in `application-postgres.yml`).** Each *running* job holds
a DB connection, so the job executor is sized against the Hikari pool:

| Setting | Value | Why |
|---|---|---|
| `core-pool-size` / `max-pool-size` | 3 / **5** | fits the 512 MB / small-CPU instance |
| `queue-capacity` | 10 | short buffer for bursts |
| `max-jobs-per-acquisition` | 3 | small batch per poll |
| `lock-time-in-millis` | 300000 (5 min) | long enough to finish a job before re-acquisition |
| `wait-time-in-millis` | 5000 (5 s) | idle poll interval |
| Hikari `maximum-pool-size` | 8 | connection pool |

**Invariant (enforced by `JobExecutorConfigTest`):** job-executor `max-pool-size` **< Hikari
`maximum-pool-size`**, so at most 5 of the 8 connections are held by running jobs and background
work can never starve the request path. Request (Tomcat) threads hold a connection only briefly
per query, so the practical peak is well within 8.

**`deployment-aware: false` (deliberate).** Because every node runs identical deployments, each
node should acquire **any** due job — this gives balanced load and failover: a job is never
stranded on a node that has gone away. `deployment-aware: true` is for *heterogeneous* apps sharing
one engine DB (each running only its own deployments), which is not this topology.

**History:** `history-level: full` is retained by design (#29); growth is handled by expanding
storage / archival (above), not a cleanup window — so the executor + history writes are not
throttled by a background cleanup batch.
