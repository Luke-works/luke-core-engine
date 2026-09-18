package com.luke.engine.payments;

import com.luke.engine.audit.AdminAuditService;
import com.luke.engine.capability.form.FormInstance;
import com.luke.engine.capability.form.FormInstanceRepository;
import com.luke.engine.capability.form.FormInstanceStates;
import com.luke.engine.capability.form.FormSubmissionService;
import com.luke.engine.capability.form.SubmissionSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The life of a submission's charge: pricing it at submit ({@link PaymentGate}), creating the
 * PaymentIntent, settling it from Stripe's verified state, and releasing abandoned ones.
 *
 * <h2>Order of events</h2>
 * <ol>
 *   <li>Submit: the answers are validated and priced SERVER-side; the instance is saved as
 *       {@code AWAITING_PAYMENT} with a {@code CREATING} {@link FormPayment} — and NOT released to its
 *       process or workflows.</li>
 *   <li>After that commits, {@link #startIntent} creates a card-only PaymentIntent on the tenant's
 *       connected account (idempotent per payment row) and returns its client secret to the payer.</li>
 *   <li>The payer confirms in the browser. Only a Stripe-reported state ever moves the charge:
 *       {@link #sync} (the payer's page asking us to check), the Connect webhook, and the
 *       {@link PaymentReconciler} all call {@link #settle} with an intent READ FROM STRIPE.</li>
 *   <li>On {@code succeeded} (and only then) the submission becomes {@code SUBMITTED} and is queued —
 *       so a workflow can never start for an unpaid form.</li>
 *   <li>Unpaid past the TTL: the intent is cancelled at Stripe FIRST, then the submission is released
 *       (an embed submission is cancelled; a recipient's form reopens so they can try again).</li>
 * </ol>
 *
 * <p>Stripe calls are never made inside a database transaction; the transactions around them are
 * explicit and short, and settlement takes the payment row's lock.
 *
 * <p><b>A payment row is the only record of an intent.</b> It is never deleted while its intent could
 * still take money: {@link #record} refuses to replace one, and an intent that turns up for an attempt
 * that already ended is cancelled at Stripe and kept on the row until Stripe confirms the cancel.
 */
@Service
public class FormPaymentService implements PaymentGate {

    private static final Logger log = LoggerFactory.getLogger(FormPaymentService.class);
    private static final int RECONCILE_BATCH = 100;
    /** After this many consecutive failed reconciler runs a row is logged as needing attention. */
    static final int ATTENTION_AFTER_ATTEMPTS = 12;
    /** How often an UNRESOLVED row (access already lost) is looked at again. */
    static final int UNRESOLVED_RETRY_HOURS = 24;
    private static final String UNAVAILABLE = "This form can't take payments right now. Please contact the form owner.";
    private static final String UNRESOLVED_MESSAGE =
            "Stripe access to this charge was lost before it settled; check the Stripe dashboard for its outcome.";

    private final PaymentsProperties props;
    private final StripeConnectGateway stripe;
    private final PaymentAccountService accounts;
    private final FormPaymentRepository payments;
    private final FormInstanceRepository instances;
    private final ObjectProvider<FormSubmissionService> submissions;
    private final AdminAuditService audit;
    private final TransactionTemplate tx;

    public FormPaymentService(PaymentsProperties props, StripeConnectGateway stripe, PaymentAccountService accounts,
                              FormPaymentRepository payments, FormInstanceRepository instances,
                              ObjectProvider<FormSubmissionService> submissions, AdminAuditService audit,
                              PlatformTransactionManager txManager) {
        this.props = props;
        this.stripe = stripe;
        this.accounts = accounts;
        this.payments = payments;
        this.instances = instances;
        this.submissions = submissions;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    /* ── the gate (inside the submit transaction) ─────────────────────────── */

    @Override
    public Optional<String> paymentKey(String schemaJson) {
        return REFUSE.paymentKey(schemaJson);
    }

    @Override
    public PendingCharge price(FormInstance inst, String schemaJson, Map<String, Object> cleanedData,
                               SubmissionSource source) {
        String door = source == null ? null : source.via();
        if (!SubmissionSource.VIA_EMBED.equals(door) && !SubmissionSource.VIA_RESPOND.equals(door)) {
            // The in-app door is a staff member filling the form; taking a card there would be staff
            // keying in someone else's card. The payer must use the form's own link.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This form takes a payment, so it has to be submitted by the payer through its link.");
        }
        String tenantId = inst.getTenantId();
        PaymentAmountResolver.Resolution r = PaymentAmountResolver.resolve(schemaJson, cleanedData);

        // A form reopened after it was paid (returned for correction) must not be charged twice.
        Optional<FormPayment> previous = inst.getId() == null ? Optional.empty() : payments.findByInstanceId(inst.getId());
        if (previous.isPresent() && FormPayment.PROCESSING.equals(previous.get().getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your payment is still being processed. Please try again shortly.");
        }
        if (previous.isPresent() && FormPayment.SUCCEEDED.equals(previous.get().getStatus())) {
            FormPayment paid = previous.get();
            if (paid.getAmountRefunded() > 0 || paid.isDisputed()) {
                // A refunded or disputed payment is not a payment: releasing the form as "paid" would run its
                // workflow for money the tenant gave back (or may lose). Charging again is the tenant's call.
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This form's payment was refunded or disputed, so it can't be submitted again here. Please contact the form owner.");
            }
            if (r.ok() && r.amountMinor() == paid.getAmountMinor() && r.currency().equals(paid.getCurrency())) {
                return new PendingCharge(r.key(), paid.getAmountMinor(), paid.getCurrency(), paid.getQuantity(), paid.getMode(),
                        paid.getDescription(), paid.getStripeAccountId(), paid.isLivemode(), door, true, paid.getIntentId());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This form was already paid for, and the changes would change the amount. Please contact the form owner.");
        }

        if (!accounts.ready(tenantId)) {
            log.info("Refused a paid submission for tenant {} form {}: payments not ready (enabled={}, plan={})",
                    tenantId, inst.getDefinitionCode(), props.enabled(), accounts.planAllows(tenantId));
            throw new ResponseStatusException(HttpStatus.CONFLICT, UNAVAILABLE);
        }
        PaymentAccount account = accounts.readyAccount(tenantId).orElseThrow();
        if (!r.ok()) {
            if (!r.failure().isPayerFixable()) {
                log.warn("Paid submission refused for tenant {} form {} v{}: payment misconfigured ({})",
                        tenantId, inst.getDefinitionCode(), inst.getVersion(), r.failure().code());
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, r.failure().payerMessage());
        }
        return new PendingCharge(r.key(), r.amountMinor(), r.currency(), r.quantity(), r.mode(),
                r.description(), account.getStripeAccountId(), account.isLivemode(), door, false, null);
    }

    @Override
    public void record(FormInstance inst, PendingCharge charge) {
        if (charge.alreadyPaid()) return; // the existing SUCCEEDED row stands
        FormPayment p = new FormPayment();
        p.setTenantId(inst.getTenantId());
        p.setInstanceId(inst.getId());
        p.setFormCode(inst.getDefinitionCode());
        p.setFormVersion(inst.getVersion());
        p.setDoor(charge.door());
        p.setFieldKey(charge.fieldKey());
        p.setStripeAccountId(charge.accountId());
        p.setLivemode(charge.livemode());
        p.setAmountMinor(charge.amountMinor());
        p.setCurrency(charge.currency());
        p.setQuantity(charge.quantity());
        p.setMode(charge.mode());
        p.setDescription(charge.description());
        p.setStatus(FormPayment.CREATING);
        // Replacing a released attempt (a recipient trying again). The old row may only go once its
        // intent can no longer take money — it is the only record of that intent.
        payments.findByInstanceId(inst.getId()).ifPresent(old -> {
            if (!replaceable(old)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, FormPayment.UNRESOLVED.equals(old.getStatus())
                        ? "There's a problem with this form's earlier payment. Please contact the form owner."
                        : "Your previous payment attempt is still being checked. Please try again in a few minutes.");
            }
            if (old.getIntentId() != null) {
                audit.record("payments.attempt.replaced", "form_payment", old.getId(), old.getTenantId(), "system", false,
                        detail("instanceId", old.getInstanceId(), "intentId", old.getIntentId(),
                                "status", old.getStatus(), "amountMinor", old.getAmountMinor()));
            }
            payments.delete(old);
            payments.flush();
        });
        payments.save(p);
    }

    /**
     * An earlier attempt whose row may be replaced: no intent was ever made, or Stripe confirmed its
     * intent cancelled. An UNRESOLVED row is kept — its intent may have taken money — until the reconciler
     * learns what happened to it (or a person does).
     */
    static boolean replaceable(FormPayment old) {
        if (FormPayment.OPEN.contains(old.getStatus()) || FormPayment.SUCCEEDED.equals(old.getStatus())) return false;
        return old.getIntentId() == null || FormPayment.CANCELED.equals(old.getStatus());
    }

    /* ── starting / resuming the charge (outside any transaction) ──────────── */

    /** What the payer's browser needs to confirm. {@code clientSecret} is null once nothing is payable. */
    public record PaymentStart(String status, String clientSecret, long amountMinor, String currency) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status);
            m.put("clientSecret", clientSecret);
            m.put("amountMinor", amountMinor);
            m.put("currency", currency);
            return m;
        }
    }

    /**
     * Create (or, for a resumed attempt, re-read) the PaymentIntent for this instance's charge.
     * Idempotent: the Stripe idempotency key is the payment row id, so a retried call returns the same
     * intent rather than a second one.
     */
    public PaymentStart startIntent(String tenantId, String instanceId) {
        FormPayment p = payments.findByInstanceIdAndTenantId(instanceId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No payment is due for this form."));
        if (FormPayment.CREATING.equals(p.getStatus())) return create(p);
        if (FormPayment.PAYABLE.contains(p.getStatus())) {
            StripeConnectGateway.IntentSnapshot s = readIntent(p);
            if (!matches(p, s) && isCancellable(s.status())) {
                // Changed at Stripe, so it can never settle here: end it now (settle records the cancel and
                // releases the submission) instead of leaving the payer stuck until the reconciler's TTL.
                log.error("PaymentIntent {} for payment {} no longer matches what was priced; cancelling it", s.id(), p.getId());
                cancelEnded(p.getId(), p.getStripeAccountId(), s.id());
                FormPayment fresh = payments.findById(p.getId()).orElse(p);
                return new PaymentStart(apiStatus(fresh), null, fresh.getAmountMinor(), fresh.getCurrency());
            }
            settle(p.getId(), s);
            // A payer is back: restart the abandonment clock, or the reconciler could cancel the charge
            // while they type their card details.
            boolean payable = matches(p, s) && touchIfPayable(p.getId());
            FormPayment fresh = payments.findById(p.getId()).orElse(p);
            return new PaymentStart(apiStatus(fresh), payable ? s.clientSecret() : null, fresh.getAmountMinor(), fresh.getCurrency());
        }
        return new PaymentStart(apiStatus(p), null, p.getAmountMinor(), p.getCurrency());
    }

    private PaymentStart create(FormPayment p) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("lukeflow_tenant", p.getTenantId());
        metadata.put("lukeflow_form", p.getFormCode());
        metadata.put("lukeflow_form_version", String.valueOf(p.getFormVersion()));
        metadata.put("lukeflow_submission", p.getInstanceId());
        metadata.put("lukeflow_payment", p.getId());
        if (p.getQuantity() != null) metadata.put("lukeflow_quantity", String.valueOf(p.getQuantity()));
        // The account may have stopped being ready since this charge was priced (a disconnect under way).
        boolean accountReady = accounts.readyAccount(p.getTenantId())
                .map(a -> a.getStripeAccountId().equals(p.getStripeAccountId())).orElse(false);
        if (!accountReady) {
            fail(p.getId(), "account_unavailable", "the Stripe account can't take payments any more");
            throw new ResponseStatusException(HttpStatus.CONFLICT, UNAVAILABLE);
        }
        StripeConnectGateway.IntentSnapshot s;
        try {
            s = stripe.createPaymentIntent(new StripeConnectGateway.CreateIntent(p.getStripeAccountId(),
                    p.getAmountMinor(), p.getCurrency(), p.getDescription(), metadata, "lukeflow-form-payment-" + p.getId()));
        } catch (StripeConnectGateway.GatewayException e) {
            log.warn("Creating the PaymentIntent for submission {} (tenant {}) failed: {}", p.getInstanceId(), p.getTenantId(), e.getMessage());
            if (e.accessLost()) {
                // Not the payer's doing: the account stopped granting access (revoked from Stripe's side).
                fail(p.getId(), e.code() == null ? "access_lost" : e.code(), e.getMessage());
                if (accounts.checkAccess(p.getStripeAccountId()) == PaymentAccountService.Access.LOST) {
                    accounts.accessLost(p.getStripeAccountId(), "Stripe refused a new charge on the account");
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, UNAVAILABLE);
            }
            if (e.clientError()) {
                fail(p.getId(), e.code(), e.getMessage());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This payment couldn't be started — the amount may be below what the payment provider accepts.");
            }
            // Transient (network, 5xx, rate limit, or another request holding this idempotency key): the row
            // stays CREATING, so a retry reuses the key and gets the SAME intent. The reconciler ends it if
            // nobody retries within the TTL.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The payment couldn't be started. Please try again.");
        }
        boolean mismatch = s.amount() != p.getAmountMinor() || !p.getCurrency().equalsIgnoreCase(s.currency());
        if (mismatch) {
            log.error("PaymentIntent {} for payment {} was created as {} {} but {} {} was priced", s.id(), p.getId(),
                    s.amount(), s.currency(), p.getAmountMinor(), p.getCurrency());
        }
        Boolean payable = tx.execute(t -> {
            FormPayment row = payments.lockById(p.getId()).orElse(null);
            if (row == null) return false;
            // Record the intent whatever happens next: the row is its only trace.
            if (row.getIntentId() == null) row.setIntentId(s.id());
            boolean ours = s.id().equals(row.getIntentId());
            if (ours && !mismatch && FormPayment.CREATING.equals(row.getStatus())) {
                row.setStatus(FormPayment.REQUIRES_PAYMENT);
                payments.save(row);
                instances.findById(row.getInstanceId()).ifPresent(inst -> writeValue(inst, row, "pending"));
                return true;
            }
            if (ours && mismatch && FormPayment.CREATING.equals(row.getStatus())) {
                row.setStatus(FormPayment.FAILED);
                row.setLastErrorCode("amount_mismatch");
                row.setLastErrorMessage("intent amount differs from the priced amount");
                payments.save(row);
                release(row, "failed");
                return false;
            }
            payments.save(row);
            // A concurrent start recorded this same intent first: it is still the one to pay.
            return ours && !mismatch && FormPayment.PAYABLE.contains(row.getStatus());
        });
        if (Boolean.TRUE.equals(payable)) {
            return new PaymentStart("requires_payment", s.clientSecret(), p.getAmountMinor(), p.getCurrency());
        }
        // The attempt ended while Stripe was creating the intent (the reconciler gave up on it, or the amount
        // came back wrong). Never hand out that secret; cancel the intent so it can't take money, and let
        // settle() record the cancel (or, if it somehow succeeded, the payment).
        cancelEnded(p.getId(), p.getStripeAccountId(), s.id());
        FormPayment fresh = payments.findById(p.getId()).orElse(p);
        if (mismatch) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The payment couldn't be started. Please try again.");
        }
        return new PaymentStart(apiStatus(fresh), null, fresh.getAmountMinor(), fresh.getCurrency());
    }

    /** Cancel an intent whose attempt already ended, then settle whatever Stripe says it is now. */
    private void cancelEnded(String paymentId, String accountId, String intentId) {
        StripeConnectGateway.IntentSnapshot after;
        try {
            after = stripe.cancelPaymentIntent(accountId, intentId);
        } catch (StripeConnectGateway.GatewayException e) {
            log.warn("Cancelling PaymentIntent {} of an ended attempt failed ({}); the reconciler will retry", intentId, e.getMessage());
            try {
                after = stripe.retrievePaymentIntent(accountId, intentId);
            } catch (StripeConnectGateway.GatewayException again) {
                return;
            }
        }
        settle(paymentId, after);
    }

    /** Bump a still-payable row's timestamp. Returns whether it is still payable. */
    private boolean touchIfPayable(String paymentId) {
        return Boolean.TRUE.equals(tx.execute(t -> payments.lockById(paymentId).map(row -> {
            if (!FormPayment.PAYABLE.contains(row.getStatus())) return false;
            row.onUpdate();
            row.setReconcileAttempts(0);
            payments.saveAndFlush(row);
            return true;
        }).orElse(false)));
    }

    /** Mark an attempt failed before an intent existed, and release its submission. */
    private void fail(String paymentId, String code, String message) {
        tx.executeWithoutResult(t -> payments.lockById(paymentId).ifPresent(row -> {
            if (!FormPayment.CREATING.equals(row.getStatus())) return;
            row.setStatus(FormPayment.FAILED);
            row.setLastErrorCode(code);
            row.setLastErrorMessage(message);
            payments.save(row);
            release(row, "failed");
        }));
    }

    /* ── settlement ───────────────────────────────────────────────────────── */

    /** Ask Stripe for the intent's real state and apply it. Returns the charge's status for the payer. */
    public Map<String, Object> sync(String tenantId, String instanceId) {
        FormPayment p = payments.findByInstanceIdAndTenantId(instanceId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No payment is due for this form."));
        if (p.getIntentId() != null && p.isUnsettled()) {
            settle(p.getId(), readIntent(p));
        }
        FormPayment fresh = payments.findById(p.getId()).orElse(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", apiStatus(fresh));
        out.put("amountMinor", fresh.getAmountMinor());
        out.put("currency", fresh.getCurrency());
        if (fresh.getLastErrorMessage() != null && FormPayment.PAYABLE.contains(fresh.getStatus())) {
            out.put("message", "The payment didn't go through. Please try again.");
        }
        return out;
    }

    /** Webhook entry point: an intent changed. Unknown intents (the tenant's other Stripe activity) are ignored. */
    public void onIntentEvent(String intentId, String eventAccountId) {
        Optional<FormPayment> found = payments.findByIntentId(intentId);
        if (found.isEmpty()) return;
        FormPayment p = found.get();
        if (eventAccountId != null && !eventAccountId.equals(p.getStripeAccountId())) {
            log.warn("Ignoring an event for intent {} from account {} — the payment belongs to {}", intentId,
                    eventAccountId, p.getStripeAccountId());
            return;
        }
        settle(p.getId(), readIntent(p));
    }

    /** Webhook entry point: {@code charge.refunded}. Refunds are the tenant's to make; we only record them. */
    public void onChargeRefunded(String intentId, long amountRefunded) {
        payments.findByIntentId(intentId).ifPresent(p -> tx.executeWithoutResult(t -> payments.lockById(p.getId()).ifPresent(row -> {
            if (amountRefunded > row.getAmountRefunded()) {
                row.setAmountRefunded(Math.min(amountRefunded, row.getAmountMinor()));
                payments.save(row);
            }
        })));
    }

    /** Webhook entry point: {@code charge.dispute.*}. The dispute's state is re-read from Stripe. */
    public void onChargeDisputed(String intentId, String eventAccountId) {
        Optional<FormPayment> found = payments.findByIntentId(intentId);
        if (found.isEmpty()) return;
        FormPayment p = found.get();
        if (eventAccountId != null && !eventAccountId.equals(p.getStripeAccountId())) return;
        recordChargeState(p.getId(), stripe.retrieveChargeState(p.getStripeAccountId(), p.getIntentId()));
    }

    /**
     * Before a paid submission is submitted again (a correction): re-read its charge's refunds and
     * disputes from Stripe, so a refund or chargeback the webhook never delivered can't pass for a
     * payment. Fails closed — when Stripe can't be asked, the resubmission waits.
     */
    public void refreshSettled(String tenantId, String instanceId) {
        FormPayment p = payments.findByInstanceIdAndTenantId(instanceId, tenantId).orElse(null);
        if (p == null || !FormPayment.SUCCEEDED.equals(p.getStatus()) || p.getIntentId() == null) return;
        StripeConnectGateway.ChargeState c;
        try {
            c = stripe.retrieveChargeState(p.getStripeAccountId(), p.getIntentId());
        } catch (StripeConnectGateway.GatewayException e) {
            log.warn("Checking the settled charge of submission {} failed: {}", instanceId, e.getMessage());
            throw e.accessLost()
                    ? new ResponseStatusException(HttpStatus.CONFLICT, UNAVAILABLE)
                    : new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Couldn't check this form's earlier payment right now. Please try again.");
        }
        recordChargeState(p.getId(), c);
    }

    private void recordChargeState(String paymentId, StripeConnectGateway.ChargeState c) {
        tx.executeWithoutResult(t -> payments.lockById(paymentId).ifPresent(row -> {
            long refunded = Math.min(Math.max(row.getAmountRefunded(), c.amountRefunded()), row.getAmountMinor());
            boolean disputed = row.isDisputed() || c.disputed();
            if (refunded == row.getAmountRefunded() && disputed == row.isDisputed()) return;
            row.setAmountRefunded(refunded);
            row.setDisputed(disputed);
            payments.save(row);
            if (disputed) {
                audit.record("payments.disputed", "form_payment", row.getId(), row.getTenantId(), "stripe", false,
                        detail("instanceId", row.getInstanceId(), "intentId", row.getIntentId()));
            }
        }));
    }

    private StripeConnectGateway.IntentSnapshot readIntent(FormPayment p) {
        try {
            return stripe.retrievePaymentIntent(p.getStripeAccountId(), p.getIntentId());
        } catch (StripeConnectGateway.GatewayException e) {
            log.warn("Reading PaymentIntent {} failed: {}", p.getIntentId(), e.getMessage());
            if (e.accessLost() && lost(p, e, "Stripe refused access to the intent") != PaymentAccountService.Access.UNKNOWN) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This payment can't be completed any more. Please contact the form owner.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't check the payment right now. Please try again.");
        }
    }

    /**
     * Stripe refused an intent as gone or inaccessible. Ask about the ACCOUNT before acting — only an
     * account-level answer tells a revoked grant (disconnect it, end every open charge on it) from a
     * missing intent (end this one) — and do nothing while even that can't be answered.
     */
    private PaymentAccountService.Access lost(FormPayment p, StripeConnectGateway.GatewayException e, String why) {
        PaymentAccountService.Access access = accounts.checkAccess(p.getStripeAccountId());
        switch (access) {
            case LOST -> accounts.accessLost(p.getStripeAccountId(), why);
            case OK -> markUnresolved(p.getId(), e.code() == null ? "access_lost" : e.code(), why + " (the intent is gone)");
            default -> log.warn("Couldn't tell whether Stripe account {} still grants access; will retry", p.getStripeAccountId());
        }
        return access;
    }

    /**
     * Apply an intent state READ FROM STRIPE to the payment row, under its lock. The only path that can
     * mark a charge paid or release a submission to its process.
     */
    void settle(String paymentId, StripeConnectGateway.IntentSnapshot s) {
        tx.executeWithoutResult(t -> {
            FormPayment row = payments.lockById(paymentId).orElse(null);
            if (row == null || s == null || row.getIntentId() == null || !row.getIntentId().equals(s.id())) return;
            if (FormPayment.SUCCEEDED.equals(row.getStatus()) || FormPayment.CANCELED.equals(row.getStatus())) {
                return; // final
            }
            // FAILED / UNRESOLVED rows were already released, but an intent exists: Stripe still has the last
            // word on whether money moved, so succeeded and canceled are recorded; nothing else changes them.
            boolean ended = FormPayment.FAILED.equals(row.getStatus()) || FormPayment.UNRESOLVED.equals(row.getStatus());
            if (!matches(row, s) && !"canceled".equals(s.status())) {
                // Someone changed the intent in Stripe. A cancel is still safe to record (it can't take money);
                // anything else is never settled — and money that moved at the wrong figure is escalated.
                log.error("PaymentIntent {} does not match payment {} (mode/amount/currency) — not settling", s.id(), row.getId());
                if ("succeeded".equals(s.status())) {
                    // Escalated once; later reads of the same intent add nothing.
                    if (!FormPayment.UNRESOLVED.equals(row.getStatus())) {
                        unresolve(row, "intent_mismatch", "the intent succeeded with a different amount, currency or mode");
                    }
                } else {
                    row.setLastErrorCode("intent_mismatch");
                    payments.save(row);
                }
                return;
            }
            switch (s.status() == null ? "" : s.status()) {
                case "succeeded" -> markPaid(row);
                case "processing", "requires_capture" -> {
                    if (!ended) row.setStatus(FormPayment.PROCESSING);
                    payments.save(row);
                }
                case "canceled" -> {
                    row.setStatus(FormPayment.CANCELED);
                    row.setCanceledAt(LocalDateTime.now());
                    payments.save(row);
                    release(row, "failed");
                }
                default -> {
                    // requires_payment_method / requires_confirmation / requires_action: still payable —
                    // unless the attempt already ended, in which case the reconciler cancels it.
                    if (ended) return;
                    row.setStatus(FormPayment.REQUIRES_PAYMENT);
                    if (s.lastErrorCode() != null || s.lastErrorMessage() != null) {
                        row.setLastErrorCode(s.lastErrorCode());
                        row.setLastErrorMessage(s.lastErrorMessage());
                    }
                    payments.save(row);
                }
            }
        });
    }

    private void markPaid(FormPayment row) {
        String before = row.getStatus();
        row.setStatus(FormPayment.SUCCEEDED);
        row.setPaidAt(LocalDateTime.now());
        row.setLastErrorCode(null);
        row.setLastErrorMessage(null);
        payments.save(row);
        FormInstance inst = instances.findById(row.getInstanceId()).orElse(null);
        if (inst == null) {
            log.error("Payment {} succeeded but its submission {} no longer exists", row.getId(), row.getInstanceId());
            return;
        }
        Map<String, Object> paid = PaymentGate.paymentValue("paid", row.getAmountMinor(), row.getCurrency(), row.getIntentId());
        if (FormInstanceStates.AWAITING_PAYMENT.equals(inst.getState())) {
            submissions.getObject().completeAfterPayment(inst, row.getFieldKey(), paid);
            return;
        }
        // The submission was released before Stripe reported success. Cancellation runs before release,
        // so this should be impossible; if it happens, the money is real — keep the record and flag it.
        writeValue(inst, row, "paid");
        log.error("Payment {} succeeded after its submission {} was released (state {}) — needs attention",
                row.getId(), inst.getId(), inst.getState());
        audit.record("payments.paid_after_release", "form_instance", inst.getId(), inst.getTenantId(), "stripe", false,
                detail("paymentId", row.getId(), "intentId", row.getIntentId(), "state", inst.getState(), "previousStatus", before));
    }

    /** Release an unpaid submission: an embed submission is cancelled; a recipient's form reopens. */
    private void release(FormPayment row, String valueStatus) {
        instances.findById(row.getInstanceId()).ifPresent(inst -> {
            if (!FormInstanceStates.AWAITING_PAYMENT.equals(inst.getState())) return;
            Map<String, Object> data = new LinkedHashMap<>(inst.getData() == null ? Map.of() : inst.getData());
            data.put(row.getFieldKey(), PaymentGate.paymentValue(valueStatus, row.getAmountMinor(), row.getCurrency(), row.getIntentId()));
            inst.setData(data);
            inst.setState(SubmissionSource.VIA_RESPOND.equals(row.getDoor())
                    ? FormInstanceStates.IN_PROGRESS : FormInstanceStates.CANCELLED);
            instances.save(inst);
        });
    }

    /**
     * The platform can no longer act on this charge (access revoked, or the intent is gone): end it as
     * UNRESOLVED, release its submission and flag it for a human.
     */
    void markUnresolved(String paymentId, String code, String why) {
        tx.executeWithoutResult(t -> payments.lockById(paymentId).ifPresent(row -> {
            if (row.isUnsettled() && !FormPayment.UNRESOLVED.equals(row.getStatus())) unresolve(row, code, why);
        }));
    }

    /** Inside a transaction holding the row's lock. */
    private void unresolve(FormPayment row, String code, String why) {
        String before = row.getStatus();
        row.setStatus(FormPayment.UNRESOLVED);
        row.setLastErrorCode(code);
        row.setLastErrorMessage(UNRESOLVED_MESSAGE);
        payments.save(row);
        release(row, "failed");
        log.error("Payment {} (submission {}, tenant {}) is UNRESOLVED: {} ({})", row.getId(), row.getInstanceId(),
                row.getTenantId(), why, code);
        audit.record("payments.unresolved", "form_payment", row.getId(), row.getTenantId(), "system", false,
                detail("instanceId", row.getInstanceId(), "intentId", row.getIntentId(), "stripeAccountId",
                        row.getStripeAccountId(), "previousStatus", before, "reason", why));
    }

    /** Whether Stripe's intent is the charge this row priced (same mode, amount and currency). */
    static boolean matches(FormPayment row, StripeConnectGateway.IntentSnapshot s) {
        return s.livemode() == row.isLivemode() && s.amount() == row.getAmountMinor()
                && row.getCurrency().equalsIgnoreCase(s.currency());
    }

    private void writeValue(FormInstance inst, FormPayment row, String status) {
        Map<String, Object> data = new LinkedHashMap<>(inst.getData() == null ? Map.of() : inst.getData());
        data.put(row.getFieldKey(), PaymentGate.paymentValue(status, row.getAmountMinor(), row.getCurrency(), row.getIntentId()));
        inst.setData(data);
        instances.save(inst);
    }

    /* ── reconciliation ───────────────────────────────────────────────────── */

    /** {@link #reconcileStale(Duration)} with no time budget. */
    public int reconcileStale() {
        return reconcileStale(null);
    }

    /**
     * Settle or release every unsettled charge untouched for longer than the TTL, oldest first, stopping
     * when {@code budget} runs out. A row Stripe can't be reached for is retried on a later run (its
     * timestamp is bumped so it rotates behind the rest); one this platform lost access to is ended as
     * UNRESOLVED. Returns how many rows were handled.
     */
    public int reconcileStale(Duration budget) {
        Instant stopAt = budget == null ? null : Instant.now().plus(budget);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(props.pendingTtlMinutes());
        List<FormPayment> stale = payments.findStale(FormPayment.OPEN, cutoff, PageRequest.of(0, RECONCILE_BATCH));
        int handled = 0;
        for (int i = 0; i < stale.size(); i++) {
            if (i > 0 && stopAt != null && Instant.now().isAfter(stopAt)) { // every run makes progress
                log.info("Payment reconciliation hit its time budget; {} stale charge(s) left for the next run", stale.size() - i);
                break;
            }
            FormPayment p = stale.get(i);
            try {
                reconcileOne(p, cutoff);
                handled++;
            } catch (StripeConnectGateway.GatewayException e) {
                if (e.accessLost() && !FormPayment.UNRESOLVED.equals(p.getStatus())
                        && lost(p, e, "Stripe refused access to the intent") != PaymentAccountService.Access.UNKNOWN) {
                    handled++;
                } else {
                    deferred(p, e, cutoff);
                }
            } catch (RuntimeException e) {
                deferred(p, e, cutoff);
            }
        }
        return handled;
    }

    /**
     * Stripe couldn't be asked about this row this run. Stamp it so the NEXT run takes it again, behind
     * every row that was already waiting — or, for an UNRESOLVED row (access already lost), in a day.
     */
    private void deferred(FormPayment p, RuntimeException e, LocalDateTime cutoff) {
        Integer attempts = tx.execute(t -> payments.lockById(p.getId()).map(row -> {
            row.setReconcileAttempts(row.getReconcileAttempts() + 1);
            row.touchAt(FormPayment.UNRESOLVED.equals(row.getStatus())
                    ? cutoff.plusHours(UNRESOLVED_RETRY_HOURS) : cutoff);
            payments.save(row);
            return FormPayment.UNRESOLVED.equals(row.getStatus()) ? -1 : row.getReconcileAttempts();
        }).orElse(0));
        int n = attempts == null ? 0 : attempts;
        if (n < 0) {
            log.info("Payment {} is still unresolved (Stripe: {}); checking again later", p.getId(), e.getMessage());
        } else if (n >= ATTENTION_AFTER_ATTEMPTS) {
            log.error("Reconciling payment {} (tenant {}) has failed {} runs in a row — needs attention: {}",
                    p.getId(), p.getTenantId(), n, e.getMessage());
        } else {
            log.warn("Reconciling payment {} failed (attempt {}); will retry: {}", p.getId(), n, e.getMessage());
        }
    }

    /** What reconcileOne found under the row's lock. */
    private record Claim(String kind, String intentId, String statusBefore) {}

    /**
     * Settle one unsettled row from Stripe, cancelling it first when it is unpaid. {@code cutoff}: the
     * reconciler's staleness bound (a row touched since — a payer came back — is left alone); null when a
     * disconnect is closing everything.
     *
     * <p>A payable row is CLAIMED under its lock before any Stripe call (marked FAILED, submission not yet
     * released), so a payer resuming at that moment is never handed a secret the cancel is about to void.
     * If the intent turns out not to be cancellable (it is processing or already paid) the claim is
     * undone and the row settles normally.
     */
    private void reconcileOne(FormPayment snapshot, LocalDateTime cutoff) {
        String claimCode = cutoff != null ? "abandoned" : "account_disconnected";
        Claim claim = tx.execute(t -> {
            FormPayment row = payments.lockById(snapshot.getId()).orElse(null);
            if (row == null || !row.isUnsettled()) return new Claim("skip", null, null);
            if (cutoff != null && !row.getUpdatedAt().isBefore(cutoff)) return new Claim("skip", null, null);
            if (row.getIntentId() == null) return new Claim("no-intent", null, row.getStatus());
            String before = row.getStatus();
            if (FormPayment.REQUIRES_PAYMENT.equals(before)) {
                row.setStatus(FormPayment.FAILED);
                row.setLastErrorCode(claimCode);
                row.setLastErrorMessage(cutoff != null ? "unpaid past the time limit" : "the Stripe account is being disconnected");
                payments.save(row);
                return new Claim("claimed", row.getIntentId(), before);
            }
            return new Claim("check", row.getIntentId(), before);
        });
        if (claim == null || "skip".equals(claim.kind())) return;
        if ("no-intent".equals(claim.kind())) {
            // Never got as far as an intent (the payer's page died, or the create call never returned).
            fail(snapshot.getId(), "abandoned", "no payment intent was created");
            if (cutoff == null) {
                // A disconnect can't leave it at that: a create that won the race has an intent to close.
                payments.findById(snapshot.getId())
                        .filter(fresh -> fresh.getIntentId() != null && fresh.isUnsettled())
                        .ifPresent(fresh -> reconcileOne(fresh, null));
            }
            return;
        }
        String account = snapshot.getStripeAccountId();
        boolean claimed = "claimed".equals(claim.kind());
        StripeConnectGateway.IntentSnapshot current = stripe.retrievePaymentIntent(account, claim.intentId());
        boolean endIt = claimed || FormPayment.FAILED.equals(claim.statusBefore())
                || FormPayment.UNRESOLVED.equals(claim.statusBefore());
        if (endIt && isCancellable(current.status())) {
            // Cancel at Stripe FIRST, so the form is only released once the intent can no longer succeed.
            try {
                current = stripe.cancelPaymentIntent(account, claim.intentId());
            } catch (StripeConnectGateway.GatewayException e) {
                if (!e.clientError()) throw e; // not reached: try again later (the claim stands meanwhile)
                // Refused: it moved on (e.g. succeeded) between the read and the cancel — settle what it is now.
                current = stripe.retrievePaymentIntent(account, claim.intentId());
            }
        }
        if (claimed && !"canceled".equals(current.status()) && !"succeeded".equals(current.status())) {
            unclaim(snapshot.getId(), claimCode); // processing (or otherwise alive): settle it as the open charge it is
        }
        settle(snapshot.getId(), current);
        // A charge still unsettled stays in the set with a fresh timestamp until Stripe resolves it.
        tx.executeWithoutResult(t -> payments.lockById(snapshot.getId()).ifPresent(row -> {
            row.setReconcileAttempts(0);
            if (row.isUnsettled()) row.touchAt(LocalDateTime.now());
            payments.save(row);
        }));
    }

    private void unclaim(String paymentId, String claimCode) {
        tx.executeWithoutResult(t -> payments.lockById(paymentId).ifPresent(row -> {
            if (!FormPayment.FAILED.equals(row.getStatus()) || !claimCode.equals(row.getLastErrorCode())) return;
            row.setStatus(FormPayment.REQUIRES_PAYMENT);
            row.setLastErrorCode(null);
            row.setLastErrorMessage(null);
            payments.save(row);
        }));
    }

    private List<FormPayment> unsettledOn(String tenantId, String accountId) {
        return payments.findUnsettledByTenant(tenantId, FormPayment.OPEN).stream()
                .filter(p -> accountId.equals(p.getStripeAccountId()))
                .filter(p -> !FormPayment.UNRESOLVED.equals(p.getStatus())) // already flagged; can't block a disconnect
                .toList();
    }

    private static final String STILL_PROCESSING = "A payment is still being processed by Stripe. Try again once it has settled.";
    private static final String CLOSE_FAILED = "Couldn't reach Stripe to close this workspace's open payments. Please try again.";

    /**
     * Before a tenant's Stripe account is disconnected (and while it takes no new charges): end every
     * unsettled charge on it while access still exists — cancelled at Stripe first, so no released
     * submission can still be paid. Re-reads the set until it is empty (a charge being created during
     * the first pass is caught by the next). Refuses while a payment is PROCESSING (it may yet succeed),
     * and when Stripe can't be reached or a charge won't close.
     */
    public void closeUnsettled(String tenantId, String accountId) {
        for (int pass = 0; pass < 3; pass++) {
            List<FormPayment> open = unsettledOn(tenantId, accountId);
            if (open.isEmpty()) return;
            if (open.stream().anyMatch(p -> FormPayment.PROCESSING.equals(p.getStatus()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, STILL_PROCESSING);
            }
            for (FormPayment p : open) {
                try {
                    reconcileOne(p, null);
                } catch (StripeConnectGateway.GatewayException e) {
                    if (!e.accessLost() || lost(p, e, "disconnecting the Stripe account") == PaymentAccountService.Access.UNKNOWN) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, CLOSE_FAILED);
                    }
                }
            }
        }
        List<FormPayment> left = unsettledOn(tenantId, accountId);
        if (left.isEmpty()) return;
        throw new ResponseStatusException(
                left.stream().anyMatch(p -> FormPayment.PROCESSING.equals(p.getStatus())) ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY,
                left.stream().anyMatch(p -> FormPayment.PROCESSING.equals(p.getStatus())) ? STILL_PROCESSING : CLOSE_FAILED);
    }

    /**
     * Access to the account is already gone (deauthorized from Stripe's side): nothing can be cancelled,
     * so every unsettled charge on it ends as UNRESOLVED and its submission is released.
     */
    public void abandonUnsettled(String tenantId, String accountId, String why) {
        payments.findUnsettledByTenant(tenantId, FormPayment.OPEN).stream()
                .filter(p -> accountId.equals(p.getStripeAccountId()))
                .forEach(p -> markUnresolved(p.getId(), "account_deauthorized", why));
    }

    /** The intents of a tenant's unsettled charges — for a best-effort cancel when the tenant is purged. */
    public List<String[]> unsettledIntents(String tenantId) {
        return payments.findUnsettledByTenant(tenantId, FormPayment.OPEN).stream()
                .filter(p -> p.getIntentId() != null)
                .map(p -> new String[] {p.getStripeAccountId(), p.getIntentId()})
                .toList();
    }

    private static boolean isCancellable(String status) {
        return "requires_payment_method".equals(status) || "requires_confirmation".equals(status)
                || "requires_action".equals(status);
    }

    /** Best-effort cancel — for a purged tenant, whose rows are about to disappear. */
    public void cancelQuietly(String accountId, String intentId) {
        try {
            stripe.cancelPaymentIntent(accountId, intentId);
        } catch (StripeConnectGateway.GatewayException e) {
            log.warn("Cancelling PaymentIntent {} failed: {}", intentId, e.getMessage());
        }
    }

    /* ── views ────────────────────────────────────────────────────────────── */

    /** The payer-facing status word for a payment row. */
    static String apiStatus(FormPayment p) {
        return switch (p.getStatus()) {
            case FormPayment.SUCCEEDED -> "succeeded";
            case FormPayment.PROCESSING -> "processing";
            case FormPayment.CANCELED, FormPayment.UNRESOLVED -> "canceled";
            case FormPayment.FAILED -> "failed";
            default -> "requires_payment";
        };
    }

    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1] == null ? null : String.valueOf(kv[i + 1]));
        return m;
    }

    /** The staff view of a submission's charge (never the client secret). */
    public Optional<Map<String, Object>> staffView(String tenantId, String instanceId) {
        return payments.findByInstanceIdAndTenantId(instanceId, tenantId).map(p -> {
            Map<String, Object> v = new LinkedHashMap<>();
            // Staff see UNRESOLVED as its own word: it needs a look in Stripe, unlike an ordinary cancel.
            v.put("status", FormPayment.UNRESOLVED.equals(p.getStatus()) ? "unresolved" : apiStatus(p));
            v.put("amountMinor", p.getAmountMinor());
            v.put("currency", p.getCurrency());
            v.put("quantity", p.getQuantity());
            v.put("mode", p.getMode());
            v.put("description", p.getDescription());
            v.put("amountRefunded", p.getAmountRefunded());
            v.put("disputed", p.isDisputed());
            v.put("livemode", p.isLivemode());
            v.put("intentId", p.getIntentId());
            v.put("stripeAccountId", p.getStripeAccountId());
            v.put("lastErrorCode", p.getLastErrorCode());
            v.put("createdAt", p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
            v.put("paidAt", p.getPaidAt() == null ? null : p.getPaidAt().toString());
            return v;
        });
    }

    /** What a public render needs to mount the card form, or null when the form takes no payment. */
    public Map<String, Object> publicConfig(String tenantId, String schemaJson) {
        return publicConfig(tenantId, schemaJson, null);
    }

    /**
     * {@link #publicConfig(String, String)} for a specific submission: {@code alreadyPaid} says its charge
     * already succeeded (a paid form reopened for correction), so the page must not ask for a card again.
     */
    public Map<String, Object> publicConfig(String tenantId, String schemaJson, String instanceId) {
        if (!PaymentAmountResolver.hasPayment(schemaJson)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<FormPayment> existing = instanceId == null ? Optional.empty()
                : payments.findByInstanceIdAndTenantId(instanceId, tenantId);
        boolean alreadyPaid = existing
                .filter(p -> FormPayment.SUCCEEDED.equals(p.getStatus()) && p.getAmountRefunded() == 0 && !p.isDisputed())
                .isPresent();
        out.put("alreadyPaid", alreadyPaid);
        if (alreadyPaid) {
            out.put("paidAmountMinor", existing.get().getAmountMinor());
            out.put("paidCurrency", existing.get().getCurrency());
        }
        Optional<PaymentAccount> account = accounts.ready(tenantId) ? accounts.readyAccount(tenantId) : Optional.empty();
        out.put("available", account.isPresent());
        out.put("provider", "stripe");
        out.put("publishableKey", account.isPresent() ? props.publishableKey() : null);
        out.put("accountId", account.map(PaymentAccount::getStripeAccountId).orElse(null));
        out.put("scriptUrl", props.stripeJsUrl());
        return out;
    }
}
