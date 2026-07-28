package com.luke.engine.recipient;

/**
 * SPI for delivering a portal OTP over a channel. {@link EmailPortalOtpSender} ships and is always
 * available; {@link SmsPortalOtpSender} is the seam the user chose — present so the SMS channel is
 * wired end-to-end, but {@link #available()} is {@code false} until a real SMS gateway bean is
 * configured. {@link PortalService} picks the sender by {@link PortalChannel} and refuses the
 * challenge with a clear message when the chosen channel is unavailable.
 */
public interface PortalOtpSender {

    /** The channel this sender delivers on. */
    PortalChannel channel();

    /** Whether delivery is actually wired (false = seam present but no provider configured). */
    boolean available();

    /**
     * Best-effort deliver {@code code} to {@code contact} (email address or phone) for a tenant.
     * Returns a short status string (e.g. {@code "SENT"} / {@code "FAILED"}) — never throws for a
     * delivery failure; callers treat the OTP as issued regardless (the code is already stored).
     * MUST only be called when {@link #available()} is true.
     */
    String send(String tenantId, String contact, String code, String user);
}
