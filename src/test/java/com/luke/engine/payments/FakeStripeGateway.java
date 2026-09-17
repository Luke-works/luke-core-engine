package com.luke.engine.payments;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory Stripe for the payments tests: intents live in a map, and each test decides what state
 * "Stripe" reports. Records every call so a test can assert exactly what would have been sent.
 */
public class FakeStripeGateway implements StripeConnectGateway {

    public final List<CreateIntent> created = new ArrayList<>();
    public final List<String> cancelled = new ArrayList<>();
    public final List<String> retrieved = new ArrayList<>();
    public final List<String> deauthorized = new ArrayList<>();
    public final Map<String, IntentSnapshot> intents = new ConcurrentHashMap<>();
    /** Accounts that have revoked this platform: every call on them fails as Stripe's does. */
    public final java.util.Set<String> revoked = ConcurrentHashMap.newKeySet();
    public GatewayException failDeauthorize;
    public GatewayException failChargeState;
    /** What "Stripe" reports about each intent's charge after it succeeded. */
    public final Map<String, ChargeState> chargeStates = new ConcurrentHashMap<>();
    public GatewayException failAccountRead;
    /** Intents whose reads fail transiently (a Stripe blip). */
    public final java.util.Set<String> transientRead = ConcurrentHashMap.newKeySet();
    /** Intents whose reads get a 403 that is NOT about account access (a restricted key's permission). */
    public final java.util.Set<String> forbiddenRead = ConcurrentHashMap.newKeySet();
    /** Runs once at the start of a read of that intent — lets a test act "while Stripe is being asked". */
    public final Map<String, Runnable> beforeRetrieve = new ConcurrentHashMap<>();
    /** Intents Stripe has lost (deleted test data): reads fail with resource_missing. */
    public final java.util.Set<String> missing = ConcurrentHashMap.newKeySet();
    private final Map<String, String> byIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    public GatewayException failCreate;
    public GatewayException failCancel;
    /** Runs when a cancel is attempted, before any failure — lets a test move the intent "meanwhile". */
    public Runnable beforeCancel;
    public GatewayException failExchange;
    public ConnectedAccount nextConnected = new ConnectedAccount("acct_fake", false, "read_write");
    public AccountSnapshot account = new AccountSnapshot("acct_fake", true, true, "Fake Co", "usd", "US");
    /** When set, a created intent reports this amount instead of the requested one. */
    public Long createAmountOverride;

    public void reset() {
        created.clear();
        cancelled.clear();
        retrieved.clear();
        deauthorized.clear();
        intents.clear();
        revoked.clear();
        missing.clear();
        transientRead.clear();
        forbiddenRead.clear();
        beforeRetrieve.clear();
        chargeStates.clear();
        failChargeState = null;
        failDeauthorize = null;
        failAccountRead = null;
        byIdempotencyKey.clear();
        failCreate = null;
        failCancel = null;
        beforeCancel = null;
        failExchange = null;
        nextConnected = new ConnectedAccount("acct_fake", false, "read_write");
        account = new AccountSnapshot("acct_fake", true, true, "Fake Co", "usd", "US");
        createAmountOverride = null;
    }

    /** Make "Stripe" report a new status for an intent. */
    public void setStatus(String intentId, String status) {
        setStatus(intentId, status, null, null);
    }

    public void setStatus(String intentId, String status, String errorCode, String errorMessage) {
        IntentSnapshot s = intents.get(intentId);
        intents.put(intentId, new IntentSnapshot(s.id(), status, s.amount(), s.currency(), s.clientSecret(), s.livemode(),
                errorCode, errorMessage));
    }

    public void setAmount(String intentId, long amount) {
        IntentSnapshot s = intents.get(intentId);
        intents.put(intentId, new IntentSnapshot(s.id(), s.status(), amount, s.currency(), s.clientSecret(), s.livemode(),
                s.lastErrorCode(), s.lastErrorMessage()));
    }

    public String onlyIntentId() {
        if (intents.size() != 1) throw new IllegalStateException("expected exactly one intent, have " + intents.keySet());
        return intents.keySet().iterator().next();
    }

    @Override
    public String authorizeUrl(String state) {
        return "https://connect.example/oauth/authorize?state=" + state;
    }

    @Override
    public ConnectedAccount exchangeCode(String code) {
        if (failExchange != null) throw failExchange;
        return nextConnected;
    }

    private static GatewayException noAccess(String accountId) {
        return new GatewayException("no access to " + accountId, "account_invalid", true, 403, true, null);
    }

    @Override
    public void deauthorize(String accountId) {
        if (failDeauthorize != null) throw failDeauthorize;
        deauthorized.add(accountId);
    }

    @Override
    public AccountSnapshot retrieveAccount(String accountId) {
        if (revoked.contains(accountId)) throw noAccess(accountId);
        if (failAccountRead != null) throw failAccountRead;
        return new AccountSnapshot(accountId, account.chargesEnabled(), account.detailsSubmitted(), account.displayName(),
                account.defaultCurrency(), account.country());
    }

    @Override
    public synchronized IntentSnapshot createPaymentIntent(CreateIntent request) {
        created.add(request);
        if (failCreate != null) throw failCreate;
        if (revoked.contains(request.accountId())) throw noAccess(request.accountId());
        String existing = byIdempotencyKey.get(request.idempotencyKey());
        if (existing != null) return intents.get(existing);
        String id = "pi_fake_" + seq.incrementAndGet();
        long amount = createAmountOverride != null ? createAmountOverride : request.amountMinor();
        IntentSnapshot s = new IntentSnapshot(id, "requires_payment_method", amount,
                request.currency().toLowerCase(), id + "_secret_x", false, null, null);
        intents.put(id, s);
        byIdempotencyKey.put(request.idempotencyKey(), id);
        return s;
    }

    @Override
    public IntentSnapshot retrievePaymentIntent(String accountId, String intentId) {
        retrieved.add(intentId);
        if (revoked.contains(accountId)) throw noAccess(accountId);
        Runnable hook = beforeRetrieve.remove(intentId);
        if (hook != null) hook.run();
        if (transientRead.contains(intentId)) throw new GatewayException("blip", "api_error", false, 500, false, null);
        if (forbiddenRead.contains(intentId)) throw new GatewayException("no permission", null, false, 403, false, null);
        IntentSnapshot s = missing.contains(intentId) ? null : intents.get(intentId);
        if (s == null) throw new GatewayException("no such intent", "resource_missing", true, 404, true, null);
        return s;
    }

    @Override
    public ChargeState retrieveChargeState(String accountId, String intentId) {
        if (revoked.contains(accountId)) throw noAccess(accountId);
        if (failChargeState != null) throw failChargeState;
        return chargeStates.getOrDefault(intentId, new ChargeState(0, false));
    }

    @Override
    public IntentSnapshot cancelPaymentIntent(String accountId, String intentId) {
        cancelled.add(intentId);
        if (revoked.contains(accountId)) throw noAccess(accountId);
        if (beforeCancel != null) beforeCancel.run();
        if (failCancel != null) throw failCancel;
        setStatus(intentId, "canceled");
        return intents.get(intentId);
    }
}
