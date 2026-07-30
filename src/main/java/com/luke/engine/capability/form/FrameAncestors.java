package com.luke.engine.capability.form;

import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/**
 * Builds and validates the CSP {@code frame-ancestors} directive from a form's per-tenant embed
 * allowlist (Route B M2). The allowlist is a user-supplied, comma/space/newline-separated list of
 * web origins (the sites a tenant permits to embed their published form); this class is the single
 * place that (a) sanitizes that input down to well-formed origins and (b) renders the directive
 * that the public embed surface emits to authoritatively control who may frame the form.
 *
 * <p>Origins only — scheme + host (one optional leading {@code *.} wildcard label) + optional port,
 * never a path/query (CSP {@code frame-ancestors} is host-source based). Anything that doesn't parse
 * is dropped, so a malformed entry can never widen the policy or inject extra directive tokens.
 *
 * <p>An empty/blank allowlist means "any site may embed" → {@code *} (the public default, preserving
 * current behaviour); a non-empty allowlist restricts framing to exactly those origins.
 */
public final class FrameAncestors {

    private FrameAncestors() {}

    // scheme://[*.]host[:port] — lowercase host chars, digits, dots, hyphens; one optional wildcard
    // label; optional 1–5 digit port. No userinfo, no path, no spaces (spaces separate directive tokens).
    private static final Pattern ORIGIN =
            Pattern.compile("^https?://(\\*\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*(:\\d{1,5})?$", Pattern.CASE_INSENSITIVE);

    /**
     * Sanitize a raw allowlist into a canonical comma-separated list of valid origins (trimmed,
     * lower-cased, de-duplicated, trailing slash removed), or {@code null} if nothing valid remains.
     * Splitting on commas AND whitespace tolerates however a user pastes the domains.
     */
    public static String normalizeList(String raw) {
        if (raw == null) return null;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String token : raw.split("[,\\s]+")) {
            String o = token.trim();
            if (o.isEmpty()) continue;
            if (o.endsWith("/")) o = o.substring(0, o.length() - 1);
            if (ORIGIN.matcher(o).matches()) out.add(o.toLowerCase());
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    /**
     * The CSP {@code frame-ancestors} directive VALUE (space-separated origins) for an allowlist.
     * Empty/blank/all-invalid → {@code *} (any site may embed). Never returns null.
     */
    public static String directive(String allowlistCsv) {
        String norm = normalizeList(allowlistCsv);
        return norm == null ? "*" : norm.replace(',', ' ');
    }

    /**
     * True when the user TYPED an allowlist but NONE of it parsed to a valid origin — a configuration
     * mistake (e.g. "example.com" with no scheme) that must FAIL CLOSED. Callers reject such input
     * rather than store {@code null}, which would silently widen the policy to the public "*" default
     * and leave the form framable by any site. A blank/cleared value is NOT "all-invalid": that's the
     * intentional "make it public" case.
     */
    public static boolean isAllInvalid(String raw) {
        return raw != null && !raw.isBlank() && normalizeList(raw) == null;
    }

    /**
     * Would this allowlist permit {@code origin} to frame the form? Mirrors how the browser reads the
     * directive we emit, including the single leading wildcard label ({@code https://*.acme.com} matches
     * {@code https://shop.acme.com} but not {@code https://acme.com} — same as CSP).
     *
     * <p>Used ONLY to annotate the observed "Embedded on" list ("this site isn't in your allowlist"). The
     * enforcement is the CSP header itself, computed by {@link #directive}; this is a read-side
     * convenience and must never become the gate.
     */
    public static boolean allows(String allowlistCsv, String origin) {
        String norm = normalizeList(allowlistCsv);
        if (norm == null) return true;                       // no allowlist → any site may embed
        if (origin == null || origin.isBlank()) return false;
        String o = origin.trim().toLowerCase();
        if (o.endsWith("/")) o = o.substring(0, o.length() - 1);
        for (String allowed : norm.split(",")) {
            if (allowed.equals(o)) return true;
            int wildcard = allowed.indexOf("://*.");
            if (wildcard > 0) {
                // "https://*.acme.com" → scheme "https://", suffix ".acme.com"
                String scheme = allowed.substring(0, wildcard + 3);
                String suffix = allowed.substring(wildcard + 4);
                if (o.startsWith(scheme) && o.length() > scheme.length() + suffix.length()
                        && o.endsWith(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
