package com.luke.engine.payments;

import java.util.Map;

/**
 * Every call this module makes to Stripe, behind one seam so the money logic is testable without a
 * network. {@link StripeConnectApi} is the real implementation.
 *
 * <p>Charges are DIRECT charges on the tenant's connected account ({@code Stripe-Account} header):
 * funds, receipts, disputes and refunds belong to the tenant. There is never an application fee.
 */
public interface StripeConnectGateway {

    /** The Connect OAuth consent URL for this state token. */
    String authorizeUrl(String state);

    /** Exchange a one-time OAuth code for the connected account. */
    ConnectedAccount exchangeCode(String code);

    /** Revoke this platform's access to an account. */
    void deauthorize(String accountId);

    AccountSnapshot retrieveAccount(String accountId);

    IntentSnapshot createPaymentIntent(CreateIntent request);

    IntentSnapshot retrievePaymentIntent(String accountId, String intentId);

    IntentSnapshot cancelPaymentIntent(String accountId, String intentId);

    /** Refund and dispute state of an intent's latest charge. */
    ChargeState retrieveChargeState(String accountId, String intentId);

    /** The result of an OAuth code exchange. */
    record ConnectedAccount(String accountId, boolean livemode, String scope) {}

    /** What we read about a connected account. */
    record AccountSnapshot(String accountId, boolean chargesEnabled, boolean detailsSubmitted,
                           String displayName, String defaultCurrency, String country) {}

    /** What we read about a PaymentIntent. {@code clientSecret} is only ever handed to the payer. */
    record IntentSnapshot(String id, String status, long amount, String currency, String clientSecret,
                          boolean livemode, String lastErrorCode, String lastErrorMessage) {}

    /** What happened to a charge after it succeeded. */
    record ChargeState(long amountRefunded, boolean disputed) {}

    /** A card-only PaymentIntent to create on {@code accountId}. */
    record CreateIntent(String accountId, long amountMinor, String currency, String description,
                        Map<String, String> metadata, String idempotencyKey) {}

    /**
     * A Stripe call failed. {@link #clientError()} is true when Stripe rejected the request itself
     * (bad code, unknown account, a card error), false for network / 5xx / rate-limit / idempotency-
     * in-flight trouble worth retrying. {@link #accessLost()} is the permanent subset that means this
     * platform can no longer act on the object at all — access to the connected account was revoked, or
     * the object doesn't exist in this mode — so waiting will never help.
     */
    class GatewayException extends RuntimeException {
        private final String code;
        private final boolean clientError;
        private final Integer httpStatus;
        private final boolean accessLost;

        public GatewayException(String message, String code, boolean clientError, Throwable cause) {
            this(message, code, clientError, null, false, cause);
        }

        public GatewayException(String message, String code, boolean clientError, Integer httpStatus,
                                boolean accessLost, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.clientError = clientError;
            this.httpStatus = httpStatus;
            this.accessLost = accessLost;
        }

        public String code() {
            return code;
        }

        public boolean clientError() {
            return clientError;
        }

        public Integer httpStatus() {
            return httpStatus;
        }

        public boolean accessLost() {
            return accessLost;
        }
    }
}
