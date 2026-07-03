package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * #40: boot initializers are serialized across instances. On Postgres that's a real
 * advisory lock (not exercised here — needs Postgres); these cover the non-Postgres
 * pass-through, the stable/namespaced lock keys, and the fail-safe fallback.
 */
class BootCoordinatorTest {

    private static DataSource h2() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:bootcoord;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test
    void runsTaskOnNonPostgres() {
        BootCoordinator c = new BootCoordinator(h2());
        AtomicInteger ran = new AtomicInteger();
        c.runExclusive("init", ran::incrementAndGet);
        assertEquals(1, ran.get(), "task must run exactly once on H2 (pass-through, no lock)");
    }

    @Test
    void lockKeysAreStableDistinctAndNamespaced() {
        assertEquals(BootCoordinator.lockKey("a"), BootCoordinator.lockKey("a"), "stable per name");
        assertNotEquals(BootCoordinator.lockKey("a"), BootCoordinator.lockKey("b"), "distinct per name");
        // High 32 bits are the fixed "LUKE" namespace so locks can't collide with other apps.
        assertEquals(0x4C554B45L, BootCoordinator.lockKey("anything") >>> 32);
    }

    @Test
    void runsTaskEvenIfLockUnavailable() throws SQLException {
        // A DB that can't hand out a connection must NOT block boot — the idempotent task
        // still runs uncoordinated (no worse than the pre-#40 behavior).
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("pool exhausted"));
        BootCoordinator c = new BootCoordinator(broken);
        AtomicInteger ran = new AtomicInteger();
        c.runExclusive("init", ran::incrementAndGet);
        assertTrue(ran.get() == 1, "task must still run when the lock can't be acquired");
    }
}
