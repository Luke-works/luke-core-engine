package com.luke.engine.usage;

import com.luke.engine.branding.PlanCatalog;
import java.util.Locale;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * A per-tenant, per-month usage dimension that a plan meters. Each metric knows how to read its own
 * monthly allotment off a {@link PlanCatalog} tier, so limits stay derived from the pricing SSOT.
 *
 * <p>Phase 2a meters the two clear synchronous counters — form submissions and outbound emails.
 * Storage (a current-usage gauge) and AI actions (metered in luke-agents) join later.
 */
public enum UsageMetric {

    SUBMISSIONS("submissions", PlanCatalog::monthlySubmissions),
    EMAILS("emails", PlanCatalog::monthlyEmails);

    private final String id;
    private final ToIntFunction<PlanCatalog> limit;

    UsageMetric(String id, ToIntFunction<PlanCatalog> limit) {
        this.id = id;
        this.limit = limit;
    }

    public String id() { return id; }

    /** This metric's monthly allotment on the given tier; {@code -1} means unlimited. */
    public int limitFor(PlanCatalog tier) { return limit.applyAsInt(tier); }

    public static Optional<UsageMetric> fromId(String id) {
        if (id == null) return Optional.empty();
        String s = id.trim().toLowerCase(Locale.ROOT);
        for (UsageMetric m : values()) {
            if (m.id.equals(s)) return Optional.of(m);
        }
        return Optional.empty();
    }
}
