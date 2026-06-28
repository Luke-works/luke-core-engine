package com.luke.engine.capability.signature;

import java.util.Set;

/**
 * The SIGNATURE INSTANCE state machine (mirrors the forms {@code FormInstanceStates}). An instance
 * is the live, per-recipient contract a campaign starts; its state advances as recipients act and is
 * driven/tracked alongside the Camunda process. Recipients carry their own narrower lifecycle.
 */
public final class SignatureInstanceStates {

    private SignatureInstanceStates() {}

    // ── instance states ──────────────────────────────────────────────────────────────
    public static final String CREATED = "CREATED";       // row created, process not yet started
    public static final String SENT = "SENT";             // links delivered to recipients
    public static final String OPENED = "OPENED";         // a recipient opened the document
    public static final String IN_PROGRESS = "IN_PROGRESS"; // at least one recipient has signed
    public static final String COMPLETED = "COMPLETED";   // all recipients signed → closure
    public static final String DECLINED = "DECLINED";     // a recipient declined
    public static final String EXPIRED = "EXPIRED";       // passed expiresAt unsigned
    public static final String CANCELLED = "CANCELLED";   // sender voided the campaign

    // ── recipient states ─────────────────────────────────────────────────────────────
    public static final String R_PENDING = "PENDING";     // not yet their turn (sequential routing)
    public static final String R_SENT = "SENT";
    public static final String R_OPENED = "OPENED";
    public static final String R_SIGNED = "SIGNED";
    public static final String R_DECLINED = "DECLINED";

    private static final Set<String> TERMINAL = Set.of(COMPLETED, DECLINED, EXPIRED, CANCELLED);

    public static boolean isTerminal(String state) {
        return TERMINAL.contains(state);
    }

    /** Guard illegal transitions: nothing moves out of a terminal state. */
    public static boolean canTransition(String from, String to) {
        if (from == null || to == null) return false;
        if (from.equals(to)) return true;
        return !isTerminal(from);
    }
}
