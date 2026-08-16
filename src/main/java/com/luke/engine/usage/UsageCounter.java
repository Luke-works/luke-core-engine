package com.luke.engine.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A per-tenant, per-metric, per-month usage tally — the durable counter behind plan limits and, later,
 * metered billing. One row per (tenant, metric, period); the id is the deterministic composite
 * {@code tenant|metric|YYYY-MM}, which makes the increment a keyed upsert and gives a natural
 * month-over-month history (a new period = a new row, so nothing is reset).
 *
 * <p>Not tenant-filtered by the {@code TenantEntityListener}: metering must record for whatever tenant
 * a submission/email belongs to, including public-embed submits where no tenant is in the request scope.
 */
@Entity
@Table(name = "luke_usage_counter", indexes = {
        @Index(name = "idx_usage_tenant_period", columnList = "tenantId,period")
})
public class UsageCounter {

    /** {@code tenant|metric|period} — the composite key, so an increment targets exactly one row. */
    @Id
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** {@link UsageMetric#id()} — e.g. {@code submissions}, {@code emails}. */
    @Column(nullable = false)
    private String metric;

    /** {@code YYYY-MM} (UTC) — the billing month this tally covers. */
    @Column(nullable = false)
    private String period;

    @Column(name = "used_count", nullable = false)
    private long count;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public UsageCounter() {}

    public UsageCounter(String tenantId, String metric, String period, long count) {
        this.id = key(tenantId, metric, period);
        this.tenantId = tenantId;
        this.metric = metric;
        this.period = period;
        this.count = count;
    }

    /** The deterministic row key for a (tenant, metric, period). */
    public static String key(String tenantId, String metric, String period) {
        return tenantId + "|" + metric + "|" + period;
    }

    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getMetric() { return metric; }
    public String getPeriod() { return period; }
    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
