# Data Retention & PII Expiry (#53)

Core-engine writes several PII / audit trails that otherwise grow forever. A scheduled
`RetentionPurgeJob` (`com.luke.engine.retention`) enforces per-data-class retention windows by
**deleting** pure logs and **anonymizing** rows where a non-PII audit record should persist.

## Data classes & disposition

| Data class | Table | Contains | Disposition past the window |
|---|---|---|---|
| Email sends | `luke_email_messages` | recipient address, subject | **DELETE** the row |
| Form submissions | `luke_form_instances` | `data` / `prefill` / `recipient` JSON payloads | **ANONYMIZE** — null the PII columns, keep the lifecycle row (state, code, timestamps) |
| Form lifecycle events | `luke_form_audit` | actor, action | **DELETE** the row |
| Email verifications (OTP) | `luke_email_verifications` | org email / domain / name, **only once terminal** (`VERIFIED`/`EXPIRED`/`FAILED`) | **REDACT** — overwrite PII with `[redacted]`, keep the status/audit row |

Each operation is a single idempotent bulk statement keyed on the row's timestamp
(`created_at`, or `at` for form audit), indexed by `V17__retention_indexes.sql`.

## Configuration (`luke.retention.*`)

**Default-lenient: nothing runs until you opt in.** The master switch is off, every window is 0
(each class is skipped until set), and dry-run is on.

| Property | Env | Default | Meaning |
|---|---|---|---|
| `luke.retention.enabled` | `LUKE_RETENTION_ENABLED` | `false` | master switch |
| `luke.retention.dry-run` | `LUKE_RETENTION_DRY_RUN` | `true` | log what WOULD be purged, mutate nothing |
| `luke.retention.interval-ms` | `LUKE_RETENTION_INTERVAL_MS` | `86400000` (24h) | schedule |
| `luke.retention.email-messages-days` | `LUKE_RETENTION_EMAIL_MESSAGES_DAYS` | `0` (skip) | email send retention |
| `luke.retention.form-instances-days` | `LUKE_RETENTION_FORM_INSTANCES_DAYS` | `0` (skip) | submission-payload retention |
| `luke.retention.form-audit-days` | `LUKE_RETENTION_FORM_AUDIT_DAYS` | `0` (skip) | form audit retention |
| `luke.retention.email-verifications-days` | `LUKE_RETENTION_EMAIL_VERIFICATIONS_DAYS` | `0` (skip) | OTP PII retention |

### Recommended starting windows (tune per your compliance policy)

```
LUKE_RETENTION_ENABLED=true
LUKE_RETENTION_DRY_RUN=true          # keep true for the first deploy — verify the logged counts
LUKE_RETENTION_EMAIL_MESSAGES_DAYS=365
LUKE_RETENTION_FORM_INSTANCES_DAYS=730
LUKE_RETENTION_FORM_AUDIT_DAYS=730
LUKE_RETENTION_EMAIL_VERIFICATIONS_DAYS=30   # OTP PII: short — the proof is consumed quickly
```

## Rollout procedure

1. Deploy with `LUKE_RETENTION_ENABLED=true` and `LUKE_RETENTION_DRY_RUN=true` plus the windows.
2. Watch the logs: `Retention [DRY-RUN]: N <class> (window Xd)` shows what each class WOULD affect.
3. When the counts look right, set `LUKE_RETENTION_DRY_RUN=false`. The next tick applies them; the
   log line switches to `Retention [applied]: …`.
4. Because the ops are idempotent and window-relative, the daily job then keeps the tables trimmed.

## Right to erasure (tenant deletion)

Deleting a tenant (`DELETE /api/tenants/{tenantId}`, via the account/deprovisioning cascade) now
**purges that tenant's PII trails immediately** — email sends, form submissions + lifecycle events,
and OTP challenges — alongside its grants and subscriptions, so no personal data outlives the tenant.

## Notes / follow-ups

- HA: the job is idempotent (window-relative bulk ops), so a second replica running it is harmless —
  matching `DocumentRetentionPurgeJob`. A shared lock is not required.
- Signature documents have their own retention (`DocumentRetentionPurgeJob`, DOC-5) with S3 Object
  Lock; they are intentionally out of scope here.
- Not yet cascaded on tenant deletion: secrets and signature requests (separate capability-owned
  lifecycles) — tracked as a follow-up if broader erasure is required.
