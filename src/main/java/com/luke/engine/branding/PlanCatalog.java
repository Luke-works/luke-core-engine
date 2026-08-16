package com.luke.engine.branding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single source of truth for the platform's commercial PLAN tiers — the pricing model made real.
 * Mirrors {@link com.luke.engine.config.RoleCatalog}: each tier carries its limits and entitlements
 * inline, and every consumer derives from this enum so the numbers cannot drift between the billing
 * gate, the operator admin, and the self-serve {@code GET /api/plan} the UI reads.
 *
 * <p>This describes a <b>TENANT's plan</b> (what a customer bought). It is distinct from a
 * {@link com.luke.engine.capability.capability.Capability#getTier() capability's pricing band} — the
 * plan says which modules and how much usage a tenant gets; the capability's tier is a module label.
 *
 * <p>The stored value in {@link TenantPlan#getPlan()} is a tier {@link #id()} ({@code FREE / PRO /
 * BUSINESS / ENTERPRISE}). The legacy two-value model stored {@code FREE / PAID}; {@link #fromStored}
 * maps the historical {@code PAID} to {@link #PRO} (the smallest paying tier), so existing rows keep
 * exactly the features they had (removable branding + attachments). Anything unknown resolves to
 * {@link #FREE} — the fail-closed default (absent row = free), so a bad value can never silently
 * upgrade a tenant.
 *
 * <p>A limit of {@code -1} means <b>unlimited / custom</b> (Enterprise is negotiated). Amounts are
 * monthly. {@link #storageGb()} is a double because the free tier is a fraction of a gigabyte.
 */
public enum PlanCatalog {

    //          id            display       rank price  subs    ai     email  storGB seats  brand   sso    voice  self   attach  capabilities
    FREE("FREE", "Free", 0, 0, 100, 10, 60, 0.5, 1, false, false, false, false, false,
            List.of("FORMS")),
    PRO("PRO", "Pro", 1, 39, 2_000, 500, 2_000, 5, 3, true, false, false, false, true,
            List.of("FORMS", "EMAIL")),
    BUSINESS("BUSINESS", "Business", 2, 149, 15_000, 2_000, 15_000, 25, 10, true, true, false, false, true,
            List.of("FORMS", "EMAIL", "SIGNATURES", "CALENDAR")),
    ENTERPRISE("ENTERPRISE", "Enterprise", 3, -1, -1, -1, -1, -1, -1, true, true, true, true, true,
            List.of("FORMS", "EMAIL", "SIGNATURES", "CALENDAR", "PHONE", "WORKFLOW", "SLA"));

    /** Legacy stored value from the original two-tier (FREE|PAID) model — aliases to {@link #PRO}. */
    public static final String LEGACY_PAID = "PAID";

    private final String id;
    private final String displayName;
    private final int rank;
    private final int priceUsd;            // monthly; -1 = custom (Enterprise)
    private final int monthlySubmissions;  // -1 = unlimited
    private final int monthlyAiActions;    // -1 = unlimited
    private final int monthlyEmails;       // -1 = unlimited
    private final double storageGb;        // -1 = unlimited
    private final int seats;               // -1 = unlimited
    private final boolean removableBranding;
    private final boolean sso;
    private final boolean voice;
    private final boolean selfHost;
    private final boolean attachments;
    private final List<String> includedCapabilities;

    PlanCatalog(String id, String displayName, int rank, int priceUsd, int monthlySubmissions,
                int monthlyAiActions, int monthlyEmails, double storageGb, int seats,
                boolean removableBranding, boolean sso, boolean voice, boolean selfHost, boolean attachments,
                List<String> includedCapabilities) {
        this.id = id;
        this.displayName = displayName;
        this.rank = rank;
        this.priceUsd = priceUsd;
        this.monthlySubmissions = monthlySubmissions;
        this.monthlyAiActions = monthlyAiActions;
        this.monthlyEmails = monthlyEmails;
        this.storageGb = storageGb;
        this.seats = seats;
        this.removableBranding = removableBranding;
        this.sso = sso;
        this.voice = voice;
        this.selfHost = selfHost;
        this.attachments = attachments;
        this.includedCapabilities = includedCapabilities;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public int rank() { return rank; }
    public int priceUsd() { return priceUsd; }
    public int monthlySubmissions() { return monthlySubmissions; }
    public int monthlyAiActions() { return monthlyAiActions; }
    public int monthlyEmails() { return monthlyEmails; }
    public double storageGb() { return storageGb; }
    public int seats() { return seats; }
    public boolean removableBranding() { return removableBranding; }
    public boolean sso() { return sso; }
    public boolean voice() { return voice; }
    public boolean selfHost() { return selfHost; }
    public boolean attachments() { return attachments; }
    public List<String> includedCapabilities() { return includedCapabilities; }

    /** True when this tier is allowed to subscribe to the given capability {@code code}. */
    public boolean includes(String capabilityCode) {
        return capabilityCode != null && includedCapabilities.contains(capabilityCode.trim().toUpperCase(Locale.ROOT));
    }

    /** True for any paying tier (rank &gt; FREE) — the historical {@code isPaid()} notion. */
    public boolean isPaid() {
        return rank > FREE.rank;
    }

    /**
     * Resolve a stored plan string to a tier. {@code null} / blank / unknown → {@link #FREE}
     * (fail-closed). The legacy {@code PAID} value → {@link #PRO}. Case-insensitive; trims.
     */
    public static PlanCatalog fromStored(String stored) {
        if (stored == null) return FREE;
        String s = stored.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return FREE;
        if (LEGACY_PAID.equals(s)) return PRO;
        for (PlanCatalog t : values()) {
            if (t.id.equals(s)) return t;
        }
        return FREE;
    }

    /** The set of valid tier ids an operator may set (excludes the legacy {@code PAID} alias). */
    public static List<String> ids() {
        return java.util.Arrays.stream(values()).map(PlanCatalog::id).toList();
    }

    /** A JSON-friendly view of the tier's limits + entitlements. {@code null} = unlimited / custom. */
    public Map<String, Object> toView() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", id);
        out.put("displayName", displayName);
        out.put("rank", rank);
        out.put("priceUsd", priceUsd < 0 ? null : priceUsd);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("monthlySubmissions", monthlySubmissions < 0 ? null : monthlySubmissions);
        limits.put("monthlyAiActions", monthlyAiActions < 0 ? null : monthlyAiActions);
        limits.put("monthlyEmails", monthlyEmails < 0 ? null : monthlyEmails);
        limits.put("storageGb", storageGb < 0 ? null : storageGb);
        limits.put("seats", seats < 0 ? null : seats);
        out.put("limits", limits);
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("removableBranding", removableBranding);
        features.put("sso", sso);
        features.put("voice", voice);
        features.put("selfHost", selfHost);
        features.put("attachments", attachments);
        out.put("features", features);
        out.put("capabilities", includedCapabilities);
        return out;
    }
}
