package com.luke.engine.usage;

/**
 * Supplies a tenant's <b>current</b> stored-bytes total — a live gauge, not a monthly tally. Storage
 * doesn't fit the {@link UsageCounter} flow model (you don't <i>accumulate</i> storage, you <i>occupy</i>
 * it), so {@link UsageService} reads it through this SPI and folds it into the {@code /api/usage}
 * snapshot alongside the counted metrics.
 *
 * <p>Implemented by the module that actually owns the bytes (the document registry). Kept an
 * interface so {@code usage} doesn't depend on {@code document}, and so the gauge can be stubbed in
 * tests. Implementations must be cheap and total for a tenant.
 */
public interface StorageUsageProvider {

    /** Bytes the tenant currently has stored (0 for a blank/unknown tenant). Never negative. */
    long usedBytes(String tenantId);
}
