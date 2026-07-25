package com.luke.engine.tenant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.util.Set;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * #21: the tenant filter aspect must FAIL CLOSED — when the filter is registered but no tenant is
 * set on the thread (async/job/backfill, or a request with no X-Tenant-Id), scope tenant-aware
 * queries to a sentinel that matches nothing, never leave them unscoped (= all tenants' rows).
 */
class TenantFilterAspectTest {

    private final Session session = mock(Session.class);
    private final SessionFactory sessionFactory = mock(SessionFactory.class);
    private final Filter filter = mock(Filter.class);
    private final EntityManager em = mock(EntityManager.class);

    private TenantFilterAspect aspect() {
        when(em.unwrap(Session.class)).thenReturn(session);
        when(session.getSessionFactory()).thenReturn(sessionFactory);
        when(session.enableFilter("tenantFilter")).thenReturn(filter);
        when(filter.setParameter(eq("tenantId"), org.mockito.ArgumentMatchers.any())).thenReturn(filter);
        return new TenantFilterAspect(em);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void noOpWhenFilterNotRegistered() {
        when(sessionFactory.getDefinedFilterNames()).thenReturn(Set.of()); // no TenantAwareEntity today
        TenantContext.set("tenant-a");

        aspect().enableTenantFilter();

        verify(session, never()).enableFilter("tenantFilter"); // must not throw UnknownFilterException
    }

    @Test
    void scopesToTheCurrentTenantWhenSet() {
        when(sessionFactory.getDefinedFilterNames()).thenReturn(Set.of("tenantFilter"));
        TenantContext.set("tenant-a");

        aspect().enableTenantFilter();

        verify(session).enableFilter("tenantFilter");
        verify(filter).setParameter("tenantId", "tenant-a");
    }

    @Test
    void failsClosedToASentinelWhenNoTenantSet() {
        when(sessionFactory.getDefinedFilterNames()).thenReturn(Set.of("tenantFilter"));
        // No TenantContext set (async/job thread).

        aspect().enableTenantFilter();

        // The filter IS enabled — but with the no-match sentinel, so the query returns nothing.
        verify(session).enableFilter("tenantFilter");
        verify(filter).setParameter("tenantId", TenantFilterAspect.NO_TENANT);
    }
}
