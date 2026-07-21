package com.luke.engine.capability.email;

/**
 * Lifecycle of an {@link EmailMessage}. An email is recorded as QUEUED the moment
 * we accept it, then flipped to SENT or FAILED once Postmark responds — so the
 * stored row is an audit trail of exactly what happened, never silently dropped.
 */
public final class EmailStatus {

    /** Accepted and persisted, not yet handed to Postmark. */
    public static final String QUEUED = "QUEUED";
    /** Postmark accepted the message (ErrorCode 0) and returned a MessageID. */
    public static final String SENT = "SENT";
    /** Postmark rejected it, or the call errored; see errorCode / errorMessage. */
    public static final String FAILED = "FAILED";
    /** An INBOUND message received via the public inbound webhook. */
    public static final String RECEIVED = "RECEIVED";

    private EmailStatus() {}
}
