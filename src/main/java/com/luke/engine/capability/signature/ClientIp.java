package com.luke.engine.capability.signature;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives the REAL client IP for the audit trail. The service sits behind a gateway/CDN, so
 * the socket address is the proxy — we read {@code X-Forwarded-For} (taking the LEFT-MOST
 * PUBLIC hop, i.e. skipping the proxy's own private-range hops) then {@code X-Real-IP},
 * falling back to the socket address. Parsing is defensive against malformed headers.
 *
 * <p>SECURITY NOTE: a client can forge {@code X-Forwarded-For}. "Left-most public hop"
 * defeats naive private-range padding, but for strong attribution prod should pin the trusted
 * proxy hop count (right-most-minus-N). Tracked for hardening; V1 records the best-effort IP.
 */
public final class ClientIp {

    private ClientIp() {}

    public static String resolve(HttpServletRequest request) {
        if (request == null) return null;

        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String firstAny = null;
            for (String part : xff.split(",")) {
                String ip = clean(part);
                if (ip == null) continue;
                if (firstAny == null) firstAny = ip;
                if (isPublic(ip)) return ip;       // left-most PUBLIC hop
            }
            if (firstAny != null) return firstAny; // all private → left-most anyway
        }

        String xri = clean(request.getHeader("X-Real-IP"));
        if (xri != null) return xri;

        return request.getRemoteAddr();
    }

    /** Trim, drop an empty/"unknown" token, and strip a trailing :port (IPv4 / bracketed IPv6). */
    private static String clean(String raw) {
        if (raw == null) return null;
        String ip = raw.trim();
        if (ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) return null;
        if (ip.startsWith("[")) {                       // [::1]:443
            int close = ip.indexOf(']');
            if (close > 0) return ip.substring(1, close);
        }
        int colon = ip.indexOf(':');
        if (colon > 0 && ip.indexOf(':', colon + 1) < 0) { // exactly one colon → ipv4:port
            return ip.substring(0, colon);
        }
        return ip;
    }

    /** True for routable addresses; false for private / loopback / link-local / unique-local. */
    private static boolean isPublic(String ip) {
        if (ip == null) return false;
        String s = ip.toLowerCase();
        if (s.equals("127.0.0.1") || s.startsWith("127.")) return false;
        if (s.startsWith("10.")) return false;
        if (s.startsWith("192.168.")) return false;
        if (s.startsWith("169.254.")) return false;          // link-local
        if (s.startsWith("172.")) {                          // 172.16.0.0 – 172.31.255.255
            int second = secondOctet(s);
            if (second >= 16 && second <= 31) return false;
        }
        if (s.equals("::1") || s.startsWith("fe80:")) return false; // ipv6 loopback / link-local
        if (s.startsWith("fc") || s.startsWith("fd")) return false; // ipv6 unique-local (fc00::/7)
        return true;
    }

    private static int secondOctet(String ipv4) {
        try {
            String[] parts = ipv4.split("\\.");
            return parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
