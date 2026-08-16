package com.luke.engine.usage;

import com.luke.engine.branding.PlanCatalog;
import com.luke.engine.branding.PlanService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-tenant, per-month usage metering + plan-limit enforcement.
 *
 * <p><b>Recording never breaks the metered operation.</b> {@link #record} runs in its OWN transaction
 * ({@code REQUIRES_NEW}) and swallows every error, so a metering hiccup can never fail (or roll back)
 * the submission/email that triggered it. The trade-off — a rare over-count if the caller's own
 * transaction later rolls back — is acceptable for approximate usage/billing.
 *
 * <p><b>Enforcement is opt-in.</b> {@link #enforce} is a no-op unless
 * {@code luke.plan.enforce-usage-limits=true} (default false), so dev/qa and prod-until-flipped only
 * COUNT — nothing is blocked. Limits come from the {@link PlanCatalog} tier via {@link PlanService};
 * an unlimited tier ({@code -1}) is never over.
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final UsageCounterRepository counters;
    private final PlanService plans;
    private final boolean enforce;

    public UsageService(UsageCounterRepository counters, PlanService plans,
                        @Value("${luke.plan.enforce-usage-limits:false}") boolean enforce) {
        this.counters = counters;
        this.plans = plans;
        this.enforce = enforce;
    }

    /** The current UTC billing period, {@code YYYY-MM}. */
    public static String currentPeriod() {
        return LocalDate.now(ZoneOffset.UTC).format(MONTH);
    }

    /** Best-effort +1 for one unit of usage. Isolated + never throws (metering must not block the op). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String tenantId, UsageMetric metric) {
        if (tenantId == null || tenantId.isBlank()) return;
        String period = currentPeriod();
        String id = UsageCounter.key(tenantId, metric.id(), period);
        try {
            if (counters.increment(id) == 0) {
                try {
                    counters.save(new UsageCounter(tenantId, metric.id(), period, 1));
                } catch (RuntimeException race) {
                    // Lost the create race with a concurrent first-of-period write — the row exists now.
                    counters.increment(id);
                }
            }
        } catch (RuntimeException e) {
            log.warn("usage record failed for {} {} — skipping (metering never blocks the operation)",
                    tenantId, metric.id(), e);
        }
    }

    /** This month's count for a metric ({@code 0} when unrecorded / blank tenant). */
    @Transactional(readOnly = true)
    public long current(String tenantId, UsageMetric metric) {
        if (tenantId == null || tenantId.isBlank()) return 0;
        return counters.findById(UsageCounter.key(tenantId, metric.id(), currentPeriod()))
                .map(UsageCounter::getCount).orElse(0L);
    }

    /** Is the tenant within its allotment for this metric? Unlimited ({@code -1}) → always true. */
    @Transactional(readOnly = true)
    public boolean within(String tenantId, UsageMetric metric) {
        int limit = metric.limitFor(plans.tierOf(tenantId));
        return limit < 0 || current(tenantId, metric) < limit;
    }

    /** Enforcement gate — a NO-OP unless enforcement is on; throws 402 when the allotment is used up. */
    public void enforce(String tenantId, UsageMetric metric) {
        if (!enforce) return;
        if (!within(tenantId, metric)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Monthly " + metric.id() + " limit reached for this plan — upgrade to continue.");
        }
    }

    /** used-vs-limit for every metric this month — the shape {@code GET /api/usage} returns. */
    @Transactional(readOnly = true)
    public Map<String, Object> snapshot(String tenantId) {
        PlanCatalog tier = plans.tierOf(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", tier.id());
        out.put("period", currentPeriod());
        Map<String, Object> usage = new LinkedHashMap<>();
        for (UsageMetric m : UsageMetric.values()) {
            int limit = m.limitFor(tier);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("used", current(tenantId, m));
            row.put("limit", limit < 0 ? null : limit); // null = unlimited
            usage.put(m.id(), row);
        }
        out.put("usage", usage);
        return out;
    }
}
