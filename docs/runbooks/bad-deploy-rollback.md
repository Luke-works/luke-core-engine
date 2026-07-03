# Runbook: Bad-deploy rollback

A deploy broke an environment. Roll back fast, then diagnose.

## Decide: roll back the code, the schema, or both?

| Symptom | Likely cause | Action |
|---------|--------------|--------|
| App won't boot / 5xx everywhere | bad code, bad config/env | **Code rollback** (below) |
| Boot fails on a guard (`Refusing to start: …`) | missing prod secret under the `prod` profile (#56/#58) | Set the missing env var, or drop `,prod` temporarily; redeploy |
| Boot fails: `relation … does not exist` / column missing | a Flyway migration didn't run, or code expects a column the migration didn't add | **Schema** — see below |
| Feature broken but app healthy | logic regression | Code rollback |

## Code rollback (Render)

1. Render → `luke-core-engine` → **Deploys** → pick the last known-good deploy → **Rollback**.
   (Equivalently, revert the commit on the branch — `main` for prod, `develop` for dev/qa —
   and let CI/Render redeploy.)
2. Graceful shutdown (#44) drains the current instance; the previous image boots.
3. Confirm `/actuator/health/readiness` is `UP` and smoke-test.

## Schema considerations (Flyway, #24)

Flyway migrations are **forward-only** — there is no automatic "down". A code rollback
does **not** undo a migration that already ran.

- If the new migration is **additive** (new table/column/index), a code rollback to the
  prior version is usually safe: the extra objects are simply unused. Prefer this.
- If a migration **dropped/renamed/retyped** something the rolled-back code needs, you
  must restore that structure: hand-write a corrective forward migration (`V<n+1>__…`)
  or, in the worst case, restore the DB ([disaster-recovery.md](disaster-recovery.md)).
- **Rule going forward:** keep migrations additive and backward-compatible across one
  release (expand/contract) so code and schema can roll independently.

## Verify after rollback

- `/actuator/health` → `db` UP, overall UP.
- `flyway_schema_history` matches the expected state for the running version.
- Start one form-intake process for a known tenant and confirm completion.
- Record what shipped, what broke, and the fix in the incident log.
