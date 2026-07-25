package com.luke.engine.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * #21: persisting a tenant-aware row with no tenant in context must FAIL CLOSED with a clear error,
 * not slip through as an untenanted row / opaque NOT NULL violation.
 */
class TenantEntityListenerTest {

    static class Order extends TenantAwareEntity {}

    private final TenantEntityListener listener = new TenantEntityListener();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void throwsWhenNoTenantIdAndNoContext() {
        var e = assertThrows(IllegalStateException.class, () -> listener.setTenant(new Order()));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("Order"));
    }

    @Test
    void assignsTheContextTenantOnInsert() {
        TenantContext.set("tenant-a");
        Order o = new Order();
        listener.setTenant(o);
        assertEquals("tenant-a", o.getTenantId());
    }

    @Test
    void keepsAnExplicitlySetTenant() {
        TenantContext.set("tenant-a");
        Order o = new Order();
        o.setTenantId("tenant-b"); // explicitly set — must not be overwritten
        listener.setTenant(o);
        assertEquals("tenant-b", o.getTenantId());
    }

    @Test
    void ignoresNonTenantAwareEntities() {
        // A plain object is not a TenantAwareEntity — the listener leaves it alone (no throw).
        listener.setTenant(new Object());
    }

    @Test
    void blankContextIsTreatedAsNoTenant() {
        TenantContext.set("   ");
        assertThrows(IllegalStateException.class, () -> listener.setTenant(new Order()));
        assertNull(new Order().getTenantId());
    }
}
