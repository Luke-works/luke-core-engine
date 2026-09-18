# Form payments (Stripe Connect) — setup, go-live and incidents

Forms can take a card payment. Each workspace connects **its own Stripe account**, and charges are
**direct charges** on that account: the workspace is the merchant of record and gets the money, the
receipts, the refunds and the disputes. Lukeflow is a Connect *platform*: it never holds funds and
takes **no application fee**. Payments earn nothing per charge; they are included on the Pro plan
and above.

## How it works

1. **Connect.** A workspace owner opens **Forms → Payments** and chooses **Connect Stripe**. Stripe's
   OAuth page opens; the owner signs in to their own account and approves. Stripe redirects back to
   `/forms/payments?code=…&state=…`, and the app posts both to `POST /api/payments/connect/complete`
   with the owner's credentials. The `state` value must be one this owner started for this workspace
   in the last 15 minutes, and it can only be used once. We store the account id (`acct_…`), never
   a credential of the tenant's.
2. **Build.** The builder offers a **Payment** field, priced one of three ways:
   - a fixed amount;
   - a price per unit × a quantity the payer enters;
   - an amount the payer enters, between a minimum and a maximum.

   There is **no formula mode**: the server does not evaluate form expressions, so a browser-computed
   total can't be trusted. Publishing is refused (`409`/`422`) unless the payment is configured
   correctly **and** the workspace can take payments.
3. **Submit.** Only the embed and emailed-link (`/respond`) doors accept a payment form. The in-app
   door refuses with `409`, because staff should not key in other people's cards.
   1. `FormSubmissionService` validates the answers and prices them with `PaymentAmountResolver`. It
      ignores whatever the browser sent for the payment field.
   2. It saves the submission as **`AWAITING_PAYMENT`** with a `CREATING` row in `luke_form_payment`.
      Nothing is queued to Camunda and no `forms.submitted` event is emitted.
   3. After that commits, a **card-only** PaymentIntent is created on the connected account, with the
      payment row id as the idempotency key. Its client secret goes to the payer's browser.
   4. If Stripe can't be reached, the submission stays saved and the page offers **Try again**. The
      retry uses the same idempotency key, so it gets the same intent. If Stripe *refuses* the charge
      (for example, the amount is below its minimum), the attempt fails and the submission is released.
   5. An embedded payment form also sends the version it rendered. If a different version is live by
      the time the payer submits, the server answers `409`: the payer saw a different price. The page
      then asks the payer to confirm any amount that differs from the one shown before charging.
4. **Pay.** The payer confirms in Stripe's Payment Element, which Stripe.js loads from
   `js.stripe.com`. Three paths can change a charge, and all of them re-read the intent from Stripe:
   - the page calls `…/sync`;
   - the Connect webhook fires;
   - the reconciler runs.

   Only `succeeded` moves the submission to `SUBMITTED` and queues it: process start, workflow
   event, and usage metering.
5. **Abandoned.** A charge untouched for `pending-ttl-minutes` (default 120) is **cancelled at Stripe
   first**. Then its submission is released:
   - an embed submission becomes `CANCELLED`;
   - an emailed-link submission goes back to `IN_PROGRESS`, so the recipient can try again.

   A payer who comes back to the payment restarts that clock. The reconciler picks up an abandoned
   charge between one TTL and one TTL plus `reconcile-ms` after it was last touched.

   Before calling Stripe, the reconciler **claims** the charge under its row lock: the row turns
   `FAILED` with code `abandoned`, and the submission is not released yet. From that moment a payer
   who returns is never handed the secret the cancel is about to void.
   - If the intent turns out to be processing (or paid), the claim is undone and the charge settles
     normally.
   - If Stripe can't be reached, the claim stands and the next run retries.
6. **Access lost.** Stripe can refuse an intent with `account_invalid`, `resource_missing`, or a
   401/403 saying the key "does not have access to account". When that happens, the platform reads
   the **account** before acting:
   - **The account is unreadable too:** access was revoked. Every workspace on that account is
     disconnected, and every open charge on it ends as **`UNRESOLVED`**.
   - **The account is readable:** only this intent is gone. That charge alone ends as `UNRESOLVED`.
   - **Neither can be read right now:** nothing changes, and it is retried later.

   An `UNRESOLVED` submission is released and audited as `payments.unresolved`, and staff see it as
   *unresolved*. The reconciler checks it again once a day. If Stripe can be read again (for example,
   after a reconnect), the charge is recorded as paid or cancelled.

   Any other 401/403/404 is **not** treated as lost access. A restricted key missing a permission is
   an operator problem: it is retried and logged, and never disconnects tenants or ends charges.
   **Never point `STRIPE_SECRET_KEY` at another platform's key.** Every connected account would then
   read as revoked, and every workspace would be disconnected.

**One row per intent, never lost.** A payment row is the only record of its intent. It is replaced
only when the old intent was never created, or when Stripe confirmed it cancelled. Until then a
resubmission gets `409`:
- an open or FAILED attempt: "still being checked";
- an `UNRESOLVED` one: "contact the form owner".

Other guarantees:
- Replacing a row that had an intent writes `payments.attempt.replaced` to the audit log.
- If an intent is created for an attempt that has already ended (a slow create finishing after the
  reconciler gave up), it is cancelled at once and its secret is never handed out.
- An intent changed in Stripe (different amount, currency or mode) is cancelled as soon as a payer
  comes back to it.

**Refunds and disputes.** A refunded or disputed payment does not count as paid. The platform learns
about them from the `charge.refunded` and `charge.dispute.*` webhooks. In addition, before a paid
submission that was returned for correction is submitted again, it re-reads the charge from Stripe;
if Stripe can't be reached, that resubmission gets `502` until it can. A refunded or disputed
submission gets `409`, because charging again is the tenant's decision. A dispute is audited as
`payments.disputed` and shown on the submission.

**Preparer-owned amounts.** On an emailed-link form, a quantity or amount field that the preparer
owns is locked for the recipient. Its value comes from the preparer's prefill, both for the charge
and for the required-field checks.

The amount rules exist twice: in form-core (the payer's preview) and in Java (what is charged).
`payment-parity.json` runs in both test suites. See
`src/test/java/com/luke/engine/payments/PaymentAmountResolverParityTest.java`.

## Configuration

Payments are **off** unless all three required keys are set. Test and live keys must not be mixed:
a mismatched pair disables payments and logs a warning.

| Env var | Required | What |
|---|---|---|
| `STRIPE_SECRET_KEY` (or `STRIPE_CONNECT_SECRET_KEY`) | yes | Platform secret key. Billing already uses `STRIPE_SECRET_KEY`; payments reuse it unless the Connect variable is set. |
| `STRIPE_PUBLISHABLE_KEY` | yes | Platform `pk_…`, sent to payers' browsers. |
| `STRIPE_CONNECT_CLIENT_ID` | yes | `ca_…`, from Dashboard → Settings → Connect → Onboarding options → OAuth. |
| `STRIPE_CONNECT_WEBHOOK_SECRET` | strongly recommended | `whsec_…` of the **connected accounts** endpoint below. Without it, charges still settle through `sync` and the reconciler, but more slowly, and an account revoked from Stripe's side is only noticed the next time the account is read (the payments page re-reads an account more than 10 minutes old). |
| `LUKE_PAYMENTS_CONNECT_REDIRECT_URL` | yes | The consumer-ui page, e.g. `https://app.lukeflow.com/forms/payments`. Must be registered in Stripe. |
| `LUKE_PAYMENTS_PENDING_TTL_MINUTES` | no | Default `120`, minimum `5`. |
| `LUKE_PAYMENTS_RECONCILE_ENABLED` / `LUKE_PAYMENTS_RECONCILE_MS` | no | Default on, every 5 minutes. Safe on every node (row locks and idempotent cancels); turn it off on all but one node to save API calls. It runs on its own thread, so a slow Stripe never delays the submission outbox. |
| `LUKE_PAYMENTS_RECONCILE_BUDGET_MS` | no | Default `120000`. A run stops after this long (having handled at least one charge) and leaves the rest for the next run. |
| `STRIPE_JS_URL` | no | Default `https://js.stripe.com/v3/`, matching the API version stripe-java 28.2.0 pins. Must start with `https://js.stripe.com/`; any other value **disables payments** and logs a warning. |

## Stripe dashboard setup (per environment; test mode first)

1. **Connect → Settings → Onboarding options → OAuth**: enable OAuth for Standard accounts, copy the
   `client_id`, and add the redirect URI (`…/forms/payments`) for every environment.
2. **Connect → Settings → Branding**: set the platform name and icon shown on the consent page.
3. **Developers → Webhooks → Add endpoint**:
   - URL: `https://<core-engine host>/webhooks/stripe-connect`. Stripe calls the engine directly; the
     gateway does not proxy `/webhooks/**`.
   - Listen to: **Events on Connected accounts**.
   - Events: `payment_intent.succeeded`, `payment_intent.processing`,
     `payment_intent.payment_failed`, `payment_intent.canceled`, `payment_intent.requires_action`,
     `charge.refunded`, `charge.dispute.created`, `charge.dispute.updated`, `charge.dispute.closed`,
     `charge.dispute.funds_withdrawn`, `charge.dispute.funds_reinstated`, `account.updated`,
     `account.application.deauthorized`.
   - Copy the signing secret into `STRIPE_CONNECT_WEBHOOK_SECRET`. This is a **different** endpoint
     and secret from billing's `/webhooks/stripe`.
4. Set the env vars above and redeploy.

## Verify in test mode (before any live key exists)

1. As an owner on a Pro+ workspace: **Forms → Payments → Connect Stripe**. Use Stripe's
   test-account option. You should land back on the page showing **Ready to take payments**.
2. Build a form with a Payment field (fixed, USD 5.00). Check it in, sign it off and publish it.
   Embed it.
3. Pay with `4242 4242 4242 4242`. Check:
   - The page says the payment was received.
   - The submission is `SUBMITTED`, and its detail page shows a **Payment** section with a Stripe
     link.
   - The intake process started.
4. Pay with `4000 0000 0000 0002`. The decline shows on the field; paying again with 4242 succeeds.
   There is still only one submission.
5. Pay with `4000 0027 6000 3184` (3-D Secure). The challenge opens inside the form and completes.
6. Submit and close the tab without paying. After the TTL the submission is `CANCELLED` and the
   intent is `canceled` in Stripe.
7. Refund the payment in Stripe. The submission's Payment section shows the refunded amount.
8. **Disconnect** from Stripe's side (Settings → Connected apps). With the webhook configured, the
   payments page shows *Disconnected* at once. Without it, the page shows it on the first status read
   more than 10 minutes after the last one. Either way, the form then refuses new submissions and any
   unpaid charge is ended as *unresolved*.
9. **Disconnect** from the payments page while a charge is unpaid. That charge is cancelled at Stripe
   first. If a charge is *processing*, the disconnect is refused (`409`) until it settles.

## Go live

- Live `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY`, live `client_id`, a live webhook endpoint and
  secret, and a live redirect URI registered.
- The platform account has completed Connect platform review. A live platform can only connect live
  accounts; a test account is refused with `409`.
- Tenants reconnect in live mode. Connected test accounts show a *mode mismatch* and cannot charge.
- Check the embed page's `Content-Security-Policy` header: `script-src` must include
  `js.stripe.com`, and `frame-src` must include `js.stripe.com` and `hooks.stripe.com`.
- Embedding sites need no change. Wallets (Apple Pay / Google Pay) are deliberately off: they need
  per-domain registration on every connected account.

## Incidents

| Symptom | Likely cause | What to do |
|---|---|---|
| Submissions stuck in `AWAITING_PAYMENT` beyond the TTL | Reconciler disabled on every node, or Stripe API errors | Check the `Reconciling payment … failed (attempt n)` logs and `LUKE_PAYMENTS_RECONCILE_ENABLED`. A failing charge is retried on the next run, behind every charge that was already waiting, so it can't block the others. After 12 failed runs in a row (about an hour) it logs at ERROR, `needs attention`. |
| Paid in Stripe, but the submission never reached its process | Webhook not configured or failing, and the payer's page never synced | Check the Stripe webhook delivery log (Stripe retries for 3 days) and `STRIPE_CONNECT_WEBHOOK_SECRET`. The reconciler settles it between one TTL and one TTL plus `reconcile-ms` after the charge was last touched. |
| Audit event `payments.unresolved` | The platform lost access to a charge before it settled: account revoked, intent deleted, or keys switched to the other mode | The submission was released. Check the tenant's Stripe dashboard (the audit detail names the intent). If the payment succeeded, refund it or ask the payer to resubmit. |
| Disconnect answers `502` | Stripe was unreachable while closing a charge or revoking access | The account stays connected; try again. Any charge already taken out of the payer's hands stays closed, and the retry (or the reconciler) finishes cancelling it. `409` means a charge is still processing. |
| Payments page says *Disconnecting didn't finish* | The engine stopped in the middle of a disconnect (status `DISCONNECTING`, no new charges) | Choose **Disconnect** again. |
| Audit event `payments.disputed` | The payer opened a dispute (chargeback) | Handle it in Stripe. The submission can't be resubmitted as paid. |
| Audit event `payments.paid_after_release` | A charge succeeded after its submission was released. Should be impossible, since cancel runs before release. | The money is real, and the row is now `SUCCEEDED`, so a resubmission of an emailed-link form is not charged again. For an embed, refund in Stripe or ask the payer to resubmit, then investigate the timeline. |
| Log line `does not match payment … (mode/amount/currency)` | Someone changed the intent in Stripe | The row is flagged `intent_mismatch` and is **not** settled, and its secret is never handed out again. If the changed intent **succeeded**, the row becomes `UNRESOLVED` (see above). Investigate before touching it. |
| `Refused a paid submission … payments not ready` | Account disconnected or not charge-enabled, a plan downgrade, or keys missing | Check **Forms → Payments** for that workspace. |
| Webhook returns `400 invalid Stripe signature` | Wrong `STRIPE_CONNECT_WEBHOOK_SECRET`, or billing's secret used for this endpoint | Use the connected-accounts endpoint's secret. |

**Data.** Tables:
- `luke_payment_account` — one row per workspace;
- `luke_form_payment` — one row per paid submission; amounts only, no card data ever;
- `luke_payment_connect_state` — pruned when expired;
- `luke_payment_webhook_event` — kept 7 days for dedupe.

The tenant purge (`DELETE /api/tenants/{id}`) removes the tenant's rows from the first three tables.
It also removes the webhook-event rows for its Stripe account, unless another workspace uses that
account. Once the purge commits, it makes a best-effort attempt to cancel the tenant's unsettled
intents and to revoke the platform's access, unless another connected workspace uses the account.
The log line `Purged tenant … cancelled n open payment intent(s)` records it. A failed cancel or
revoke is only logged.

**Disconnect.** While a disconnect runs, the account is `DISCONNECTING` and takes no new charges.
The disconnect re-reads the open charges until none are left, then revokes access. If that fails, the
account goes back to `CONNECTED`.

**Shared accounts.** Two workspaces can connect the same Stripe account. The OAuth grant belongs to
the account, not to a workspace, so disconnecting one workspace does not revoke access while the
other is still connected. A revocation from Stripe's side disconnects both.

**OAuth return.** The page that finishes connecting completes it for the workspace that *started*
the connection (read from the pending state), not whichever workspace the reloaded page selected.
It then switches to that workspace. Only an owner of that workspace can finish.

Card data never touches Lukeflow: PCI scope is SAQ A, and the Payment Element runs in Stripe's
iframes.
