# Runbook: Database disaster recovery

Restore the engine's Postgres after data loss/corruption. **Read fully before acting.**

## Backups

Render takes automated backups of the managed Postgres (`luke-camunda-db`). Cadence
and retention depend on the plan — **confirm in Render → the database → Backups**.
- `basic-256mb`: daily backups (→ RPO ≈ 24h).
- Point-in-time recovery (PITR) requires a higher plan; enable it if a sub-24h RPO is
  required and update [README.md](README.md).

The database holds **both** Camunda `ACT_*` tables and the custom `luke_*` tables, so a
single Postgres restore recovers the whole engine state consistently.

## Restore procedure (restore → verify → promote)

> Never restore in place over a live database. Restore to a NEW instance, verify, then
> repoint. This keeps the damaged DB available for forensics and lets you abort safely.

1. **Stop writes.** In Render, suspend the `luke-core-engine` web service (or scale to 0)
   so nothing writes during recovery.
2. **Create a scratch instance.** Render → `luke-camunda-db` → Backups → restore the
   chosen backup/timestamp into a **new** database instance.
3. **Verify the restore** against the scratch instance (psql):
   - `\dt <schema>.act_*` and `\dt <schema>.luke_*` return the expected tables.
   - `SELECT count(*) FROM <schema>.act_ru_execution;` — running process instances look sane.
   - `SELECT version, success FROM <schema>.flyway_schema_history ORDER BY installed_rank;`
     — Flyway history present and all `success = true`.
4. **Repoint the app.** Update the web service's DB connection (env vars wired from the
   DB in `render.yaml`) to the restored instance. Keep `SPRING_PROFILES_ACTIVE` and all
   `sync:false` secrets unchanged (see below).
5. **Boot & confirm.** Resume the service. Confirm `/actuator/health/readiness` is `UP`
   and `/actuator/health` shows `db` UP. Flyway will baseline-on-migrate (no DDL re-run
   on an already-populated restore).
6. **Smoke test.** Sign in, list forms/processes for a known tenant, start one
   form-intake process, confirm it completes.
7. **Promote / decommission.** Once healthy, decommission the damaged instance after a
   hold period.

## Secrets are NOT in the database

Encrypted secret **values** live in `luke_secrets`, but the **master key**
(`LUKE_SECRETS_KEYS_V1`), embed HMAC, internal shared secret, and Postmark tokens are
env vars (`sync:false`). A DB restore alone cannot decrypt `luke_secrets` without the
same `LUKE_SECRETS_KEYS_V1`. **Back up these env values out-of-band** (a secrets manager)
— losing the master key makes all stored secrets unrecoverable. See
[../../README](../../README.md) and the `prod` profile notes (#56/#58).

## After any restore

- Re-run a tenant smoke test per active tenant if feasible.
- Record actual RTO and any data gap (RPO) in the incident log.
- If this was the **first** rehearsal: capture timings and fix any gaps in this runbook.
