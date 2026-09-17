package com.luke.engine.payments;

import com.luke.engine.audit.AdminAuditService;
import com.luke.engine.branding.PlanFeatures;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * A tenant's connected Stripe account: the Connect OAuth round-trip, status, and disconnect.
 *
 * <p>Bring-your-own-account: the tenant signs in to THEIR Stripe account and grants this platform
 * access; we keep only the account id. Charges are direct charges on that account, so the tenant is
 * merchant of record. Stripe calls happen outside database transactions; the short transactions
 * around them are explicit.
 */
@Service
public class PaymentAccountService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAccountService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final long STATE_TTL_MINUTES = 15;
    /** A status view older than this re-reads the account from Stripe. */
    private static final long SYNC_AFTER_MINUTES = 10;

    private final PaymentsProperties props;
    private final StripeConnectGateway stripe;
    private final PaymentAccountRepository accounts;
    private final PaymentConnectStateRepository states;
    private final PlanFeatures plans;
    private final AdminAuditService audit;
    private final TransactionTemplate tx;
    /** Lazy: the charge service depends on this one. */
    private final ObjectProvider<FormPaymentService> charges;
    private final PaymentWebhookEventRepository events;

    public PaymentAccountService(PaymentsProperties props, StripeConnectGateway stripe, PaymentAccountRepository accounts,
                                 PaymentConnectStateRepository states, PlanFeatures plans, AdminAuditService audit,
                                 PlatformTransactionManager txManager, ObjectProvider<FormPaymentService> charges,
                                 PaymentWebhookEventRepository events) {
        this.props = props;
        this.stripe = stripe;
        this.accounts = accounts;
        this.states = states;
        this.plans = plans;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.charges = charges;
        this.events = events;
    }

    /** The tenant's account if it can take a charge right now (connected, charges enabled, right mode). */
    public Optional<PaymentAccount> readyAccount(String tenantId) {
        if (!props.enabled() || tenantId == null) return Optional.empty();
        return accounts.findById(tenantId).filter(a -> a.isReady(props.livemode()));
    }

    /** Whether this tenant may take payments at all (platform configured + plan). */
    public boolean planAllows(String tenantId) {
        return plans.canUsePayments(tenantId);
    }

    /** Payments are fully usable: configured, on the plan, and an account ready to charge. */
    public boolean ready(String tenantId) {
        return props.enabled() && planAllows(tenantId) && readyAccount(tenantId).isPresent();
    }

    /** The status view for the settings page. Refreshes a stale connected account from Stripe. */
    public Map<String, Object> view(String tenantId, boolean canManage) {
        PaymentAccount account = accounts.findById(tenantId).orElse(null);
        if (account != null && props.enabled() && PaymentAccount.CONNECTED.equals(account.getStatus())
                && (account.getLastSyncedAt() == null
                        || account.getLastSyncedAt().isBefore(LocalDateTime.now().minusMinutes(SYNC_AFTER_MINUTES)))) {
            account = syncQuietly(account);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.enabled());
        out.put("livemode", props.livemode());
        out.put("planAllows", planAllows(tenantId));
        out.put("canManage", canManage);
        out.put("ready", props.enabled() && planAllows(tenantId) && account != null && account.isReady(props.livemode()));
        out.put("account", account == null ? null : accountView(account));
        return out;
    }

    private Map<String, Object> accountView(PaymentAccount a) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("accountId", a.getStripeAccountId());
        v.put("status", a.getStatus());
        v.put("livemode", a.isLivemode());
        v.put("chargesEnabled", a.isChargesEnabled());
        v.put("detailsSubmitted", a.isDetailsSubmitted());
        v.put("displayName", a.getDisplayName());
        v.put("defaultCurrency", a.getDefaultCurrency() == null ? null : a.getDefaultCurrency().toUpperCase(java.util.Locale.ROOT));
        v.put("country", a.getCountry());
        v.put("connectedAt", a.getConnectedAt() == null ? null : a.getConnectedAt().toString());
        v.put("modeMismatch", a.isLivemode() != props.livemode());
        return v;
    }

    private PaymentAccount syncQuietly(PaymentAccount account) {
        try {
            StripeConnectGateway.AccountSnapshot s = stripe.retrieveAccount(account.getStripeAccountId());
            return tx.execute(status -> accounts.findById(account.getId()).map(a -> {
                apply(a, s);
                return accounts.save(a);
            }).orElse(account));
        } catch (StripeConnectGateway.GatewayException e) {
            if (e.accessLost()) {
                // Revoked from Stripe's side and the webhook didn't tell us (or isn't configured).
                log.warn("Stripe account {} no longer grants access; marking it disconnected", account.getStripeAccountId());
                accessLost(account.getStripeAccountId(), "access to the Stripe account was revoked");
                return accounts.findById(account.getId()).orElse(account);
            }
            log.warn("Could not refresh Stripe account {} for tenant {}: {}", account.getStripeAccountId(), account.getId(), e.getMessage());
            return account;
        }
    }

    private static void apply(PaymentAccount a, StripeConnectGateway.AccountSnapshot s) {
        a.setChargesEnabled(s.chargesEnabled());
        a.setDetailsSubmitted(s.detailsSubmitted());
        a.setDisplayName(s.displayName());
        a.setDefaultCurrency(s.defaultCurrency());
        a.setCountry(s.country());
        a.setLastSyncedAt(LocalDateTime.now());
    }

    private void requireEnabled(String tenantId) {
        if (!props.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payments aren't available.");
        if (!planAllows(tenantId)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Taking payments needs a paid plan.");
        }
    }

    /** Begin the Connect OAuth round-trip; returns the Stripe URL to send the owner to. */
    public String startConnect(String tenantId, String userId) {
        requireEnabled(tenantId);
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tx.executeWithoutResult(s -> {
            states.deleteExpired(LocalDateTime.now());
            states.save(new PaymentConnectState(state, tenantId, userId, LocalDateTime.now().plusMinutes(STATE_TTL_MINUTES)));
        });
        return stripe.authorizeUrl(state);
    }

    /**
     * Finish the round-trip. The state must be one THIS owner started for THIS tenant, unexpired; it is
     * consumed whatever happens next, so a code can never be replayed against it.
     */
    /** The tenant a pending connection was started for — the OAuth return may land in another workspace. */
    public Optional<String> connectStateTenant(String state) {
        if (state == null || state.isBlank()) return Optional.empty();
        return states.findById(state).map(PaymentConnectState::getTenantId);
    }

    public Map<String, Object> completeConnect(String tenantId, String userId, String code, String state) {
        requireEnabled(tenantId);
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing authorization code.");
        }
        PaymentConnectState pending = tx.execute(s -> {
            PaymentConnectState found = states.findById(state).orElse(null);
            if (found != null) states.delete(found);
            return found;
        });
        if (pending == null || !pending.getTenantId().equals(tenantId) || !pending.getUserId().equals(userId)
                || pending.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This connection link has expired or wasn't started here. Please connect again.");
        }

        StripeConnectGateway.ConnectedAccount connected;
        StripeConnectGateway.AccountSnapshot snapshot;
        try {
            connected = stripe.exchangeCode(code.trim());
            if (connected.livemode() != props.livemode()) {
                // A test-mode account on a live platform (or vice versa) could never take a real charge.
                safeDeauthorize(connected.accountId());
                throw new ResponseStatusException(HttpStatus.CONFLICT, props.livemode()
                        ? "That Stripe account connected in test mode. Connect it in live mode."
                        : "That Stripe account connected in live mode, but this environment only uses test mode.");
            }
            snapshot = stripe.retrieveAccount(connected.accountId());
        } catch (StripeConnectGateway.GatewayException e) {
            log.warn("Stripe Connect completion failed for tenant {}: {}", tenantId, e.getMessage());
            throw new ResponseStatusException(e.clientError() ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY,
                    e.clientError() ? "Stripe didn't accept that connection. Please connect again."
                            : "Couldn't reach Stripe. Please try again.");
        }

        PaymentAccount saved = tx.execute(s -> {
            PaymentAccount a = accounts.findById(tenantId).orElseGet(PaymentAccount::new);
            a.setId(tenantId);
            a.setStripeAccountId(connected.accountId());
            a.setStatus(PaymentAccount.CONNECTED);
            a.setLivemode(connected.livemode());
            a.setConnectedBy(userId);
            a.setConnectedAt(LocalDateTime.now());
            a.setDisconnectedAt(null);
            apply(a, snapshot);
            return accounts.save(a);
        });
        audit.record("payments.account.connected", "payment_account", connected.accountId(), tenantId, userId, false,
                Map.of("livemode", connected.livemode(), "chargesEnabled", snapshot.chargesEnabled()));
        return view(saved.getId(), true);
    }

    /** Re-read the account from Stripe now (the owner finished onboarding in Stripe's dashboard). */
    public Map<String, Object> refresh(String tenantId) {
        PaymentAccount account = accounts.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Stripe account is connected."));
        if (props.enabled()) syncQuietly(account);
        return view(tenantId, true);
    }

    /**
     * Disconnect the workspace's account. Its unsettled charges are closed first (cancelled at Stripe
     * while access still exists), then this platform's access is revoked — unless another workspace
     * still uses the same Stripe account, since the grant is per account, not per workspace. A revoke
     * that fails for a transient reason leaves the account connected so the owner can retry.
     */
    public Map<String, Object> disconnect(String tenantId, String userId) {
        PaymentAccount account = accounts.findById(tenantId)
                .filter(PaymentAccountService::isConnected)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Stripe account is connected."));
        String acct = account.getStripeAccountId();
        boolean shared = usedElsewhere(acct, tenantId, true);
        if (props.enabled()) {
            // Take no new charges while the open ones are closed (a crashed attempt can simply be retried).
            setStatus(tenantId, PaymentAccount.DISCONNECTING, true);
            try {
                charges.getObject().closeUnsettled(tenantId, acct);
                if (!shared) {
                    try {
                        stripe.deauthorize(acct);
                    } catch (StripeConnectGateway.GatewayException e) {
                        if (!e.clientError()) {
                            log.warn("Stripe deauthorize for {} failed: {}", acct, e.getMessage());
                            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                    "Couldn't reach Stripe to disconnect. Please try again.");
                        }
                        // Stripe refused because access is already gone — the outcome we wanted.
                        log.info("Stripe deauthorize for {} refused ({}); treating as already revoked", acct, e.code());
                    }
                }
            } catch (RuntimeException e) {
                setStatus(tenantId, PaymentAccount.CONNECTED, false); // still connected: payments resume
                throw e;
            }
        }
        markDisconnected(List.of(account));
        audit.record("payments.account.disconnected", "payment_account", acct, tenantId, userId, false,
                Map.of("sharedWithAnotherWorkspace", shared));
        return view(tenantId, true);
    }

    private static boolean isConnected(PaymentAccount a) {
        return PaymentAccount.CONNECTED.equals(a.getStatus()) || PaymentAccount.DISCONNECTING.equals(a.getStatus());
    }

    /** Move a connected row between CONNECTED and DISCONNECTING (never a disconnected one). */
    private void setStatus(String tenantId, String status, boolean fromConnected) {
        tx.executeWithoutResult(t -> accounts.findById(tenantId).ifPresent(a -> {
            boolean eligible = fromConnected ? isConnected(a) : PaymentAccount.DISCONNECTING.equals(a.getStatus());
            if (!eligible) return;
            a.setStatus(status);
            accounts.save(a);
        }));
    }

    /** Whether another workspace's row (a connected one, when {@code connectedOnly}) uses this Stripe account. */
    private boolean usedElsewhere(String accountId, String tenantId, boolean connectedOnly) {
        return accounts.findByStripeAccountId(accountId).stream()
                .anyMatch(a -> !a.getId().equals(tenantId) && (!connectedOnly || isConnected(a)));
    }

    /** What an account-level read says about this platform's access to a connected account. */
    public enum Access { OK, LOST, UNKNOWN }

    /** Ask Stripe whether this platform can still read the account. Never throws. */
    public Access checkAccess(String accountId) {
        try {
            stripe.retrieveAccount(accountId);
            return Access.OK;
        } catch (StripeConnectGateway.GatewayException e) {
            return e.accessLost() ? Access.LOST : Access.UNKNOWN;
        } catch (RuntimeException e) {
            return Access.UNKNOWN;
        }
    }

    private void safeDeauthorize(String accountId) {
        try {
            stripe.deauthorize(accountId);
        } catch (StripeConnectGateway.GatewayException e) {
            // Already revoked from the Stripe side is fine; anything else is logged — the local record is
            // still marked disconnected, so no further charges are created either way.
            log.warn("Stripe deauthorize for {} failed: {}", accountId, e.getMessage());
        }
    }

    private void markDisconnected(List<PaymentAccount> rows) {
        tx.executeWithoutResult(s -> {
            for (PaymentAccount a : rows) {
                accounts.findById(a.getId()).ifPresent(fresh -> {
                    fresh.setStatus(PaymentAccount.DISCONNECTED);
                    fresh.setChargesEnabled(false);
                    fresh.setDisconnectedAt(LocalDateTime.now());
                    accounts.save(fresh);
                });
            }
        });
    }

    /* ── webhook hooks ────────────────────────────────────────────────────── */

    /** {@code account.updated}: re-read the account for every tenant using it. */
    public void onAccountUpdated(String accountId) {
        List<PaymentAccount> rows = accounts.findByStripeAccountId(accountId);
        if (rows.isEmpty()) return;
        StripeConnectGateway.AccountSnapshot s = stripe.retrieveAccount(accountId);
        tx.executeWithoutResult(t -> {
            for (PaymentAccount a : accounts.findByStripeAccountId(accountId)) {
                if (!PaymentAccount.CONNECTED.equals(a.getStatus())) continue;
                apply(a, s);
                accounts.save(a);
            }
        });
    }

    /**
     * {@code account.application.deauthorized}: the tenant revoked us from Stripe's side. Checked against
     * Stripe first — a late retry of an old event must not disconnect an account that was connected again
     * since. A transient failure of that check throws, so Stripe redelivers.
     */
    public void onDeauthorized(String accountId) {
        if (accounts.findByStripeAccountId(accountId).isEmpty()) return;
        try {
            stripe.retrieveAccount(accountId);
            log.info("Ignoring a deauthorization event for {}: this platform still has access", accountId);
            return;
        } catch (StripeConnectGateway.GatewayException e) {
            if (!e.accessLost() && !e.clientError()) throw e;
        }
        accessLost(accountId, "the Stripe account revoked this platform's access");
    }

    /**
     * Access to a Stripe account is gone (confirmed by an account-level read): disconnect every workspace
     * on it and end their open charges.
     */
    public void accessLost(String accountId, String why) {
        List<PaymentAccount> rows = accounts.findByStripeAccountId(accountId);
        List<PaymentAccount> connected = rows.stream().filter(PaymentAccountService::isConnected).toList();
        markDisconnected(connected);
        for (PaymentAccount a : rows) {
            charges.getObject().abandonUnsettled(a.getId(), accountId, why);
        }
        for (PaymentAccount a : connected) {
            audit.record("payments.account.deauthorized", "payment_account", accountId, a.getId(), "stripe", false);
        }
    }

    /**
     * Called inside the tenant-purge transaction, before the payment rows are deleted. Deletes the
     * account's webhook dedupe rows (when no other workspace uses it) and, once the purge COMMITS, makes a
     * best-effort attempt to cancel the tenant's unsettled intents — a payer still holding a client
     * secret could otherwise pay for a submission that no longer exists — and to revoke this platform's
     * access (unless another workspace is still connected to the account).
     */
    public void beforeTenantPurge(String tenantId) {
        List<String[]> intents = charges.getObject().unsettledIntents(tenantId);
        PaymentAccount account = accounts.findById(tenantId).orElse(null);
        String acct = account == null ? null : account.getStripeAccountId();
        boolean revoke = acct != null && PaymentAccount.CONNECTED.equals(account.getStatus()) && !usedElsewhere(acct, tenantId, true);
        if (acct != null && !usedElsewhere(acct, tenantId, false)) events.deleteByAccountId(acct);
        if (intents.isEmpty() && !revoke) return;
        Runnable cleanup = () -> {
            if (!props.enabled()) return;
            FormPaymentService svc = charges.getObject();
            for (String[] i : intents) svc.cancelQuietly(i[0], i[1]);
            if (revoke) safeDeauthorize(acct);
            log.info("Purged tenant {}: cancelled {} open payment intent(s){}", tenantId, intents.size(),
                    revoke ? " and revoked access to " + acct : "");
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }
}
