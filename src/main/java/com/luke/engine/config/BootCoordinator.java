package com.luke.engine.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serializes side-effecting BOOT-TIME initializers across instances so the platform is
 * safe to run with more than one replica (#40).
 *
 * <p>The startup writers (RBAC roles, parent-cluster tenant, capability seed, per-tenant
 * BPMN deploy, backfills, OTP template install) are each idempotent but were uncoordinated:
 * with N instances they all run concurrently on boot and race on first-create. Wrapping
 * each in {@link #runExclusive} fixes that:
 * <ul>
 *   <li><b>Postgres</b> (dev/qa/prod): a per-initializer {@code pg_advisory_lock} ensures
 *       exactly one instance runs a given initializer at a time. The others block briefly,
 *       then run and find the work already done (idempotent → no-op). No create races.</li>
 *   <li><b>H2 / local dev</b> (inherently single-node): straight pass-through, no lock.</li>
 * </ul>
 *
 * <p>The advisory lock is held on a dedicated connection for the duration of the task; the
 * task's own DB work uses separate pooled connections, unaffected. If the lock can't be
 * acquired (e.g. transient DB issue) the task still runs uncoordinated rather than blocking
 * boot — no worse than the previous behavior, and the writes are idempotent.
 */
@Component
public class BootCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BootCoordinator.class);

    private final DataSource dataSource;

    public BootCoordinator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Run {@code task} while holding a cluster-wide advisory lock keyed by {@code name}. */
    public void runExclusive(String name, Runnable task) {
        Connection conn = null;
        boolean locked = false;
        long key = lockKey(name);
        try {
            conn = dataSource.getConnection();
            if (isPostgres(conn)) {
                acquire(conn, key);
                locked = true;
                log.debug("BootCoordinator: holding advisory lock for '{}'", name);
            }
        } catch (SQLException e) {
            log.warn("BootCoordinator: could not acquire advisory lock for '{}' ({}); running "
                    + "uncoordinated — writes are idempotent", name, e.getMessage());
        }
        try {
            task.run();
        } finally {
            if (locked) {
                try {
                    release(conn, key);
                } catch (SQLException e) {
                    log.warn("BootCoordinator: failed to release advisory lock for '{}': {}",
                            name, e.getMessage());
                }
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // returning the connection to the pool; nothing actionable on failure
                }
            }
        }
    }

    private static boolean isPostgres(Connection conn) throws SQLException {
        return "PostgreSQL".equalsIgnoreCase(conn.getMetaData().getDatabaseProductName());
    }

    private static void acquire(Connection conn, long key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_lock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        }
    }

    private static void release(Connection conn, long key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        }
    }

    /**
     * Stable 64-bit advisory-lock key for an initializer name. High 32 bits are a fixed
     * "LUKE" namespace so these locks can't collide with any other app's advisory locks
     * on a shared Postgres; low 32 bits are the name hash so each initializer has its own
     * lock (different initializers don't block each other). Stays within signed bigint.
     */
    static long lockKey(String name) {
        return (0x4C554B45L << 32) | (name.hashCode() & 0xFFFFFFFFL);
    }
}
