package com.luke.engine.recipient;

/**
 * How a recipient portal OTP is delivered. {@code EMAIL} ships with a real sender;
 * {@code SMS} is wired behind the {@link PortalOtpSender} seam and only activates once a
 * gateway bean reports {@link PortalOtpSender#available()} true (no SMS provider exists in the
 * fleet yet — see {@link SmsPortalOtpSender}). Magic-link auth is a separate, code-less path
 * (see {@link PortalService}), not a channel here.
 */
public enum PortalChannel {
    EMAIL,
    SMS;

    /** Parse a client-supplied channel, defaulting to EMAIL for anything blank/unknown. */
    public static PortalChannel parse(String raw) {
        if (raw == null) return EMAIL;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return EMAIL;
        }
    }
}
