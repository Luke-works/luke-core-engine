package com.luke.engine.capability.email;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Heuristics tying a free-text organisation name to the domain of an "official"
 * email address, for the OTP verification flow. The OTP itself proves the person
 * controls the mailbox; this only stops an obvious mismatch — an org calling itself
 * "Acme" while verifying a {@code randomstartup.com} address.
 *
 * <p>Matching is deliberately LENIENT: normalise both sides (strip Inc/LLC/Corp,
 * punctuation, spaces) and accept when the domain's registrable root and the org
 * name overlap. It also rejects free/consumer mailbox providers, since an "official"
 * company address should not be a gmail/outlook account.
 */
public final class OrgDomainMatcher {

    /** Corporate-name noise words dropped before comparison. */
    private static final Set<String> SUFFIXES = Set.of(
            "the", "inc", "incorporated", "llc", "ltd", "limited", "corp", "corporation",
            "co", "company", "gmbh", "ag", "plc", "llp", "lp", "group", "holdings", "holding",
            "technologies", "technology", "tech", "labs", "lab", "software", "solutions",
            "services", "systems", "global", "international", "intl", "and", "of");

    /** Common two-level public suffixes so we pick the right registrable label. */
    private static final Set<String> TWO_LEVEL_TLDS = Set.of(
            "co.uk", "org.uk", "gov.uk", "ac.uk", "me.uk", "co.in", "co.jp", "com.au",
            "net.au", "org.au", "co.nz", "com.br", "co.za", "com.sg", "com.mx", "com.tr");

    /** Free/consumer mailbox providers an official company address should not use. */
    private static final Set<String> FREE_PROVIDERS = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.uk", "yahoo.in",
            "outlook.com", "hotmail.com", "hotmail.co.uk", "live.com", "msn.com",
            "icloud.com", "me.com", "mac.com", "aol.com", "proton.me", "protonmail.com",
            "pm.me", "gmx.com", "gmx.net", "mail.com", "yandex.com", "zoho.com",
            "hey.com", "fastmail.com", "tutanota.com", "qq.com", "163.com", "126.com");

    private OrgDomainMatcher() {}

    /** Outcome of a name↔domain check, with the normalized values for messaging. */
    public record MatchResult(boolean ok, String normalizedName, String domainRoot, String reason) {}

    public static boolean isFreeProvider(String domain) {
        return domain != null && FREE_PROVIDERS.contains(domain.trim().toLowerCase(Locale.ROOT));
    }

    /** The free/personal mailbox providers — the single source of truth (#38), shared
     *  with the client via {@code /api/public/meta/free-email-domains} and reused by
     *  {@code PersonalEmail}. Immutable. */
    public static Set<String> freeProviders() {
        return FREE_PROVIDERS;  // Set.of(...) is already unmodifiable
    }

    /** The registrable root label of a domain: acme.com → "acme", mail.acme.co.uk → "acme". */
    public static String domainRoot(String domain) {
        if (domain == null || domain.isBlank()) return "";
        String d = domain.trim().toLowerCase(Locale.ROOT);
        String[] parts = d.split("\\.");
        if (parts.length < 2) return normalizeToken(parts[0]);
        String lastTwo = parts[parts.length - 2] + "." + parts[parts.length - 1];
        int rootIdx = (parts.length >= 3 && TWO_LEVEL_TLDS.contains(lastTwo))
                ? parts.length - 3 : parts.length - 2;
        return normalizeToken(parts[rootIdx]);
    }

    /** Org name → a single comparable token: "The Acme Corporation" → "acme". */
    public static String normalizeName(String orgName) {
        if (orgName == null) return "";
        String[] tokens = orgName.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        StringBuilder kept = new StringBuilder();
        StringBuilder all = new StringBuilder();
        for (String t : tokens) {
            if (t.isBlank()) continue;
            all.append(t);
            if (!SUFFIXES.contains(t)) kept.append(t);
        }
        // If every token was a noise word, fall back to the full joined name.
        return kept.length() > 0 ? kept.toString() : all.toString();
    }

    /**
     * Lenient overlap: equal, or either string contains the other (min length 3 to
     * avoid trivial substrings). The OTP carries the real proof, so this only needs
     * to catch a clear name/domain disconnect.
     */
    public static MatchResult match(String orgName, String domain) {
        String name = normalizeName(orgName);
        String root = domainRoot(domain);
        if (name.isBlank() || root.isBlank()) {
            return new MatchResult(false, name, root, "Could not derive a comparable org name or domain");
        }
        boolean ok = name.equals(root)
                || (name.length() >= 3 && root.contains(name))
                || (root.length() >= 3 && name.contains(root));
        String reason = ok ? "match"
                : "Org name '" + name + "' does not match the email domain '" + root + "'";
        return new MatchResult(ok, name, root, reason);
    }

    /** The domain part of an email address, lowercased; "" if there's no '@'. */
    public static String domainOf(String email) {
        if (email == null) return "";
        int at = email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }

    /** Very light email shape check — a single '@' with text either side and a dotted domain. */
    public static boolean looksLikeEmail(String email) {
        if (email == null) return false;
        String e = email.trim();
        int at = e.indexOf('@');
        return at > 0 && at == e.lastIndexOf('@') && at < e.length() - 1
                && e.substring(at + 1).contains(".")
                && Arrays.stream(e.split("\\s")).count() == 1;
    }

    private static String normalizeToken(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
