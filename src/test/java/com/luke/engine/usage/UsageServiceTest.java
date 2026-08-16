package com.luke.engine.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.branding.PlanCatalog;
import com.luke.engine.branding.PlanService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Metering records best-effort (never throws), counts per-month, and enforcement is opt-in. */
class UsageServiceTest {

    private final UsageCounterRepository counters = mock(UsageCounterRepository.class);
    private final PlanService plans = mock(PlanService.class);

    private UsageService svc(boolean enforce) {
        return new UsageService(counters, plans, enforce);
    }

    private String key(String tenant, UsageMetric m) {
        return UsageCounter.key(tenant, m.id(), UsageService.currentPeriod());
    }

    @Test
    void recordIncrementsAnExistingRow() {
        String id = key("t1", UsageMetric.SUBMISSIONS);
        when(counters.increment(id)).thenReturn(1);
        svc(false).record("t1", UsageMetric.SUBMISSIONS);
        verify(counters).increment(id);
        verify(counters, never()).save(any());
    }

    @Test
    void recordCreatesTheRowOnFirstUseOfThePeriod() {
        String id = key("t1", UsageMetric.EMAILS);
        when(counters.increment(id)).thenReturn(0); // no row yet
        svc(false).record("t1", UsageMetric.EMAILS);
        verify(counters).save(any(UsageCounter.class));
    }

    @Test
    void recordRecoversFromACreateRaceByIncrementingAgain() {
        String id = key("t1", UsageMetric.SUBMISSIONS);
        when(counters.increment(id)).thenReturn(0);
        when(counters.save(any(UsageCounter.class))).thenThrow(new RuntimeException("duplicate key"));
        svc(false).record("t1", UsageMetric.SUBMISSIONS);
        verify(counters, times(2)).increment(id); // first (0), then again after the lost race
    }

    @Test
    void recordNeverThrowsAndBlankTenantIsANoOp() {
        when(counters.increment(anyString())).thenThrow(new RuntimeException("db down"));
        svc(false).record("t1", UsageMetric.EMAILS); // swallowed
        svc(false).record("  ", UsageMetric.EMAILS); // blank → no repo call
        verify(counters, never()).increment(key("  ", UsageMetric.EMAILS));
    }

    @Test
    void currentReadsThisMonthsCount() {
        when(counters.findById(key("t1", UsageMetric.SUBMISSIONS)))
                .thenReturn(Optional.of(new UsageCounter("t1", "submissions", UsageService.currentPeriod(), 42)));
        assertThat(svc(false).current("t1", UsageMetric.SUBMISSIONS)).isEqualTo(42);
        assertThat(svc(false).current("t1", UsageMetric.EMAILS)).isEqualTo(0); // no row
    }

    @Test
    void withinRespectsTheTierLimitAndUnlimitedNeverExceeds() {
        when(plans.tierOf("free")).thenReturn(PlanCatalog.FREE);     // 100 submissions
        when(plans.tierOf("ent")).thenReturn(PlanCatalog.ENTERPRISE); // unlimited
        when(counters.findById(key("free", UsageMetric.SUBMISSIONS)))
                .thenReturn(Optional.of(new UsageCounter("free", "submissions", UsageService.currentPeriod(), 100)));
        assertThat(svc(false).within("free", UsageMetric.SUBMISSIONS)).isFalse(); // at the cap
        assertThat(svc(false).within("ent", UsageMetric.SUBMISSIONS)).isTrue();   // unlimited
    }

    @Test
    void enforceIsANoOpWhenOffAndBlocksWithinOn() {
        when(plans.tierOf("free")).thenReturn(PlanCatalog.FREE);
        when(counters.findById(key("free", UsageMetric.SUBMISSIONS)))
                .thenReturn(Optional.of(new UsageCounter("free", "submissions", UsageService.currentPeriod(), 100)));
        // Off (default): over the cap, but no-op.
        svc(false).enforce("free", UsageMetric.SUBMISSIONS);
        // On: over the cap → 402.
        assertThatThrownBy(() -> svc(true).enforce("free", UsageMetric.SUBMISSIONS))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(402));
    }

    @Test
    void snapshotReportsUsedVsLimitPerMetric() {
        when(plans.tierOf("pro")).thenReturn(PlanCatalog.PRO);
        when(counters.findById(key("pro", UsageMetric.SUBMISSIONS)))
                .thenReturn(Optional.of(new UsageCounter("pro", "submissions", UsageService.currentPeriod(), 5)));
        var snap = svc(false).snapshot("pro");
        assertThat(snap.get("plan")).isEqualTo("PRO");
        @SuppressWarnings("unchecked")
        var usage = (java.util.Map<String, Object>) snap.get("usage");
        @SuppressWarnings("unchecked")
        var subs = (java.util.Map<String, Object>) usage.get("submissions");
        assertThat(subs.get("used")).isEqualTo(5L);
        assertThat(subs.get("limit")).isEqualTo(2000); // PRO submissions
    }
}
