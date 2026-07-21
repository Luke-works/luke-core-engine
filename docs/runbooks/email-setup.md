# Email (Postmark) setup runbook

The EMAIL capability is **code-complete** — mailbox OTP verification, per-tenant Postmark
server auto-provisioning, the OTP template installer, and sending (raw / template / workflow
`email/send` action). Turning it on in an environment needs only **Postmark provider config**;
no repo code change.

## Architecture recap

| Env var | Config key | Role |
|---|---|---|
| `POSTMARK_ACCOUNT_TOKEN` | `luke.email.postmark.account-token` | **Account** token — `PostmarkAccountClient` uses it to create a *new Postmark server per tenant* when they verify (each tenant sends on its own server + token). |
| `POSTMARK_SERVER_TOKEN` | `luke.email.postmark.server-token` | **Platform server** token — sends the OTP verification email before a tenant has its own server; `OtpTemplateInstaller` publishes the `luke-otp` template to it on boot (skips cleanly if unset). |
| `EMAIL_DEFAULT_FROM` | `luke.email.postmark.default-from` | Default From address (e.g. `no-reply@lukeflow.com`) — must be a Postmark-verified sender/domain. |
| `EMAIL_SENDER_BASE_DOMAIN` | `luke.email.sender.base-domain` | Base sending domain (already `lukeflow.com`); tenant sender addresses live under it. |

`POSTMARK_ACCOUNT_TOKEN` / `POSTMARK_SERVER_TOKEN` / `EMAIL_DEFAULT_FROM` are `sync: false` in
`render.yaml` (dashboard-entered secrets). `EMAIL_SENDER_BASE_DOMAIN=lukeflow.com` is set.

## One-time Postmark setup (needs a Postmark account + DNS access)

1. **Create/log in to Postmark.** New accounts start pending approval for bulk sending —
   request approval early (support may ask what you send: transactional OTP + app email).
2. **Account API token:** Postmark → *Account* → *API Tokens* → copy the **Account token** →
   this becomes `POSTMARK_ACCOUNT_TOKEN` (lets the engine auto-create a server per tenant).
3. **Platform (OTP) server:** create a Postmark **Server** (e.g. "Lukeflow Platform / OTP") →
   copy its **Server API token** → `POSTMARK_SERVER_TOKEN`. On boot `OtpTemplateInstaller`
   publishes the `luke-otp` template to this server.
4. **Verify the sender domain** `lukeflow.com`: Postmark → *Sender Signatures* / *Domains* →
   add `lukeflow.com` → add the **DKIM** and **Return-Path (custom)** DNS records Postmark
   shows to the lukeflow.com DNS zone → wait for Postmark to mark it *Verified*. Pick a From
   address on the verified domain (e.g. `no-reply@lukeflow.com`) → `EMAIL_DEFAULT_FROM`.
5. **Enter the 3 secrets in Render** for each env's engine service (`platform-dev-engine`,
   `platform-qa-engine`): `POSTMARK_ACCOUNT_TOKEN`, `POSTMARK_SERVER_TOKEN`, `EMAIL_DEFAULT_FROM`.
   Save → the engine redeploys and reads them.

## Verify it works (qa)

1. Consumer-ui → **Email** setup: enter org name + an official email on a domain the org
   controls → **Send code**. (OTP is sent via the platform server token.)
2. Enter the code → **Verify & finish**. On success the tenant's Postmark server is
   auto-provisioned (`GET /api/email-servers` returns it) and the org can send.
3. Send a test (email template / workflow `email/send`) and confirm delivery.

## Notes / gotchas

- The engine **boots fine with none of these set** (all default to blank; account/OTP paths
  return a clear "not configured" error and `OtpTemplateInstaller` skips) — so a missing token
  never crashes dev/qa; it just disables sending until configured.
- `EMAIL_DEFAULT_FROM` is not secret; it can instead be a plain `value:` in render.yaml
  (e.g. `no-reply@lukeflow.com`) if you'd rather not manage it as a dashboard secret.
- Per-tenant sending requires each tenant's From/domain to be deliverable under
  `EMAIL_SENDER_BASE_DOMAIN`; domain-level DKIM on `lukeflow.com` covers subdomain sends.
- Prod: the commented prod scaffold in `render.yaml` needs the same three secrets + its own
  verified domain when stood up.
