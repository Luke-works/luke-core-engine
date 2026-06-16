package com.luke.engine.backfill;

/**
 * A backward-compatibility task that runs once per startup to bring existing data in
 * line with a newly-added behavior — e.g. adding the platform admin to tenants that
 * were created before auto-provisioning existed.
 *
 * <p>Implementations MUST be idempotent: {@link #run()} executes on every boot, so it
 * has to check-before-act and do nothing when there's nothing to fix. Register one by
 * making it a Spring {@code @Component}; {@link BackfillRunner} discovers and runs all
 * of them, ordered by {@link #order()}.
 */
public interface Backfill {

    /** Stable, human-readable name used in logs. */
    String name();

    /**
     * Apply the backfill. Must be idempotent. Returns the number of records changed
     * (0 when there was nothing to do) for logging.
     */
    int run();

    /** Lower runs first. Default mid-range so tasks can slot before/after. */
    default int order() {
        return 100;
    }
}
