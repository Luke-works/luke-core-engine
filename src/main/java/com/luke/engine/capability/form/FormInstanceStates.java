package com.luke.engine.capability.form;

import java.util.Map;
import java.util.Set;

/**
 * The form-instance lifecycle and its legal transitions. Kept as String
 * constants to match the codebase's status convention (see Capability.status).
 *
 * <pre>
 *  created → sent → opened → in_progress
 *     └──────────── submit ────────────┴──► submitted ──► processed
 *  submitted ──return──► in_progress
 *  any open state ──► expired | cancelled
 *
 *  a form that takes a payment:
 *  open ──submit──► awaiting_payment ──paid──► submitted
 *                        ├──abandoned (embed)──► cancelled
 *                        └──abandoned (recipient)──► in_progress
 * </pre>
 */
public final class FormInstanceStates {

    public static final String CREATED = "CREATED";
    public static final String SENT = "SENT";
    public static final String OPENED = "OPENED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String PROCESSED = "PROCESSED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";
    /**
     * Submitted and priced, but not yet paid. NOT a received submission: it is not queued to a process
     * and emits no event until the payment provider confirms the charge (see FormPaymentService).
     */
    public static final String AWAITING_PAYMENT = "AWAITING_PAYMENT";

    /** States in which the form can still be opened/edited/submitted. */
    public static final Set<String> OPEN = Set.of(CREATED, SENT, OPENED, IN_PROGRESS);

    /** States that count as a received submission (mirrors the UI's {@code isSub}). */
    public static final Set<String> SUBMITTED_STATES = Set.of(SUBMITTED, PROCESSED);

    private static final Map<String, Set<String>> ALLOWED = Map.of(
        CREATED,     Set.of(SENT, OPENED, IN_PROGRESS, SUBMITTED, AWAITING_PAYMENT, CANCELLED, EXPIRED),
        SENT,        Set.of(OPENED, IN_PROGRESS, SUBMITTED, AWAITING_PAYMENT, CANCELLED, EXPIRED),
        OPENED,      Set.of(IN_PROGRESS, SUBMITTED, AWAITING_PAYMENT, CANCELLED, EXPIRED),
        IN_PROGRESS, Set.of(SUBMITTED, AWAITING_PAYMENT, CANCELLED, EXPIRED),
        AWAITING_PAYMENT, Set.of(SUBMITTED, IN_PROGRESS, CANCELLED),
        SUBMITTED,   Set.of(PROCESSED, IN_PROGRESS),   // IN_PROGRESS = returned for correction
        PROCESSED,   Set.of(),
        EXPIRED,     Set.of(),
        CANCELLED,   Set.of()
    );

    private FormInstanceStates() {}

    public static boolean isKnown(String state) {
        return ALLOWED.containsKey(state);
    }

    public static boolean canTransition(String from, String to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isOpen(String state) {
        return OPEN.contains(state);
    }
}
