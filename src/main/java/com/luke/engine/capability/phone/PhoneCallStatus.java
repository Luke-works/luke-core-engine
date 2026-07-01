package com.luke.engine.capability.phone;

import java.util.Set;

/**
 * Lifecycle of a {@link PhoneCall}, tracking the Vapi call from creation to its
 * terminal outcome. We accept/observe a call as QUEUED, advance it as Vapi reports
 * {@code status-update}s, and settle it at ENDED (Vapi delivered an
 * {@code end-of-call-report}) or FAILED (we never managed to place it, or Vapi
 * reported a failure ended-reason). The stored row is therefore an audit trail of
 * exactly what happened to every inbound and outbound call.
 */
public final class PhoneCallStatus {

    /** Accepted/observed and persisted; for outbound, not yet handed to Vapi. */
    public static final String QUEUED = "QUEUED";
    /** Vapi is dialing / the phone is ringing. */
    public static final String RINGING = "RINGING";
    /** The call connected and the assistant is talking. */
    public static final String IN_PROGRESS = "IN_PROGRESS";
    /** Vapi delivered the end-of-call report; transcript/recording/cost are final. */
    public static final String ENDED = "ENDED";
    /** We could not place the outbound call, or Vapi reported a failure outcome. */
    public static final String FAILED = "FAILED";

    /** Statuses past which no further lifecycle change is expected. */
    public static final Set<String> TERMINAL = Set.of(ENDED, FAILED);

    public static boolean isTerminal(String status) {
        return status != null && TERMINAL.contains(status);
    }

    /**
     * Map a Vapi {@code call.status} string (from a {@code status-update}) to our
     * lifecycle. Unknown values leave the current status unchanged (return null).
     */
    public static String fromVapi(String vapiStatus) {
        if (vapiStatus == null) return null;
        return switch (vapiStatus) {
            case "scheduled", "queued" -> QUEUED;
            case "ringing" -> RINGING;
            case "in-progress", "forwarding" -> IN_PROGRESS;
            case "ended" -> ENDED;
            default -> null;
        };
    }

    private PhoneCallStatus() {}
}
