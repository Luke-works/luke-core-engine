package com.luke.engine.capability.form;

import jakarta.servlet.http.HttpServletRequest;

/**
 * WHO submitted, from WHERE — the provenance a form submission needs to stand up as evidence.
 *
 * <p>Captured at the request edge (the only place the servlet request exists) and handed to
 * {@link FormSubmissionService#submit}, which is the single choke point every door funnels through.
 * It is written onto the {@link FormInstance} AND into the immutable {@code formMetaData} snapshot, so
 * the record travels with the submission into the process instance and onto the submission PDF.
 *
 * <p><b>Trust.</b> {@link #clientIp} prefers {@code X-Real-Client-IP} — the value the gateway resolves
 * and stamps, stripping any client-supplied one — which is spoof-resistant once core's public surface
 * is reachable only through the gateway. It falls back to the forgeable {@code X-Forwarded-For} /
 * {@code X-Real-IP} and finally the socket address, so a self-hosted or direct-to-core deployment still
 * records the best available value. Record it as "the IP we observed", not "proof of identity": for a
 * stronger claim, pair it with the recipient OTP the outbound flow already enforces.
 *
 * <p><b>Privacy.</b> An IP address is personal data under GDPR/CCPA. It is stored because the tenant
 * needs it for enforceability, and it inherits the instance's retention — purging an instance purges
 * its provenance with it.
 */
public record SubmissionSource(String ip, String userAgent, String via) {

    /** The public embed iframe on a third-party site. */
    public static final String VIA_EMBED = "EMBED";
    /** The outbound per-recipient page (/respond/{token}) or the recipient portal — OTP-verified. */
    public static final String VIA_RESPOND = "RESPOND";
    /** An authenticated tenant user filling the form in-app. */
    public static final String VIA_APP = "APP";

    /** A user-agent longer than this is a bot or an attack; store the prefix (the column is bounded). */
    private static final int UA_MAX = 512;

    /** Capture from the in-flight request. Never throws — a missing header just records null. */
    public static SubmissionSource from(HttpServletRequest request, String via) {
        if (request == null) return new SubmissionSource(null, null, via);
        return new SubmissionSource(clientIp(request), userAgent(request), via);
    }

    /**
     * The best available client IP, preferring the gateway-vouched header over the forgeable ones.
     * Shared with the public rate limiter so throttling and provenance agree on who a caller is.
     */
    public static String clientIp(HttpServletRequest req) {
        if (req == null) return null;
        String vouched = req.getHeader("X-Real-Client-IP");
        if (vouched != null && !vouched.isBlank()) return vouched.trim();
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        String remote = req.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? null : remote;
    }

    private static String userAgent(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) return null;
        ua = ua.trim();
        return ua.length() > UA_MAX ? ua.substring(0, UA_MAX) : ua;
    }
}
