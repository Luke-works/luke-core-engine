# Runbook: Incident triage

First response when the engine is degraded or erroring.

## 1. Establish scope (2 min)

- `GET /actuator/health` — overall + component status (`db`, etc.).
- `GET /actuator/health/readiness` — is the instance taking traffic? (DOWN = draining or DB down).
- `GET /actuator/health/liveness` — is the process alive?
- Render → service → Metrics: CPU, **memory** (starter = 512 MB; OOM is a known risk, #22),
  request rate, response times.
- Render → database → Metrics: connections, CPU, **storage used vs 1 GB** (history growth, #29).

## 2. Match the symptom

| Symptom | Probable cause | First action |
|---------|----------------|--------------|
| Readiness DOWN, liveness UP | DB unreachable or mid-drain | Check DB health/connections; if DB down see [disaster-recovery.md](disaster-recovery.md) |
| Memory climbing → restarts | OOM (#22): heap %, history, pool | Restart for relief; check for large payloads/leaks; consider plan bump |
| `act_id_user`/`relation does not exist` | schema/migration race or gap | [bad-deploy-rollback.md](bad-deploy-rollback.md) → schema section |
| 401/403 storms on `/api/**` | auth misconfig (gateway/operator) | Verify `sync:false` secrets are set; check `prod` profile guards (#56) |
| Postmark/email failures | upstream down / token | Email send is inline (#59) — check Postmark status + `POSTMARK_*` |
| Slow queries / disk near full | history is RETAINED by policy and grows (#29) | This is expected — expand DB storage (do NOT enable cleanup); check `ACT_HI_*` size + storage trend |

## 3. Logs

Every log line carries a correlation id: `[cid:<id>]` (set by `CorrelationIdFilter`).
Grab the `cid` from an error response header (`X-Correlation-Id`) and grep Render logs
for it to trace one request end-to-end.

## 4. Mitigations (in order of reversibility)

1. **Restart** the service (Render) — clears transient memory/connection issues.
2. **Roll back** the last deploy if the incident started at a deploy
   ([bad-deploy-rollback.md](bad-deploy-rollback.md)).
3. **Scale the plan** (web and/or DB) if it's capacity ([scaling-and-ha.md](scaling-and-ha.md)).
4. **Restore the DB** only if data is lost/corrupted ([disaster-recovery.md](disaster-recovery.md)).

## 5. After

Record timeline, root cause, and follow-ups. Many triage rows above map to open backlog
items (#22 observability/alerting, #29 history, #59 email) — link the incident to them.

> Gap: there is no metrics/alerting backend yet (#22). Until then, triage is dashboard +
> logs. Wiring metrics + alerts (storage %, memory %, error rate) is the top operability
> follow-up.
