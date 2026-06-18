# Test Cases — luke-core-engine (dev/qa)

These cover the security fixes merged to `develop` (auto-deployed to **dev/qa** on Render).
Run them against your **dev/qa** core-engine URL.

## Before you start (setup once)
- Your dev/qa core-engine URL — call it `CORE` (e.g. `https://luke-core-engine-dev.onrender.com`).
- A tenant id you belong to — call it `TENANT`.
- A way to log in to the consumer app (a normal user) and the admin/ops UI (operator).
- A terminal (Mac: open the "Terminal" app). You'll copy-paste `curl` lines.

> Tip: replace `CORE` and `TENANT` in the commands with your real values.

---

## ✅ Item 1 — Form inbox now requires login (PR #47, fixes 3 critical holes)
**What changed:** `/api/form-inbox`, `/api/process-trace`, and `/api/topics` used to work for *anyone*. Now you must be logged in (and a member of the tenant).

### Test 1a — the door is locked now (no login)
1. Paste this into the terminal (no login token on purpose):
   ```
   curl -i "$CORE/api/form-inbox" -H "X-Tenant-Id: $TENANT"
   ```
2. Look at the **very first line**.
- ✅ **PASS:** it says `HTTP/... 401 Unauthorized`. (Before the fix it was `200`.)
- ❌ **FAIL:** it says `200` and lists tasks → fix not deployed; tell the dev.

### Test 1b — topics registry is locked (no login)
1. ```
   curl -i "$CORE/api/topics"
   ```
- ✅ **PASS:** `401 Unauthorized`.
- ❌ **FAIL:** `200` with a list → not deployed.

### Test 1c — the app inbox still works for a real user
1. In the consumer app, log in as a normal user and open the **Inbox** / tasks page.
- ✅ **PASS:** your tasks load like before.
- ❌ **FAIL (important):** inbox is empty or shows an error → the app's request isn't carrying a login token core-engine accepts.
  - **Fix:** on the core-engine dev/qa service, set `LUKE_AUTH_GATEWAY_ENABLED=true` and `LUKE_AUTH_GATEWAY_JWKS_URL=<auth-engine>/.well-known/jwks.json`, then redeploy. (This is the one config this PR depends on.)

### Test 1d — you can't peek at another tenant
1. Log in as a user of tenant A, but ask for tenant B's inbox (use a tenant id you do NOT belong to as `OTHER`):
   ```
   curl -i "$CORE/api/form-inbox" -H "X-Tenant-Id: OTHER" -H "Authorization: Bearer <your-token>"
   ```
- ✅ **PASS:** `403 Forbidden` ("Not a member of tenant…").
- ❌ **FAIL:** `200` with another tenant's tasks → tell the dev immediately.

---

## ✅ Item 2 — No more `admin`/`admin` super-user (PR #48)
**What changed:** if the admin password env var isn't set, the prod profile now **refuses to start** instead of booting with the guessable default `admin`.

### Test 2a — prod profile boots fine with a real password (the normal case)
> dev/qa already sets `CAMUNDA_ADMIN_PASSWORD` (Render generates it), so this should just work.
1. Open the Render dashboard → the core-engine dev/qa service → **Logs** after the latest deploy.
- ✅ **PASS:** the service is **Live** and logs show a normal startup.
- ❌ **FAIL:** it crash-loops with "Refusing to start … blank or default password" → the env var is missing; set `CAMUNDA_ADMIN_PASSWORD` to a strong value and redeploy.

### Test 2b — you cannot log in as admin/admin
1. Try to log in to the ops UI (or call an admin endpoint) with username `admin`, password `admin`.
- ✅ **PASS:** it's rejected.
- ❌ **FAIL:** it works → the env var wasn't set; see 2a.

---

## Notes
- Items here deployed via PRs #47 and #48 (merged to `develop`).
- If Test 1c fails, that's a **config** need (gateway token), not a code bug — see the fix in 1c.
