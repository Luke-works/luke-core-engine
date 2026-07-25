package com.luke.engine.retention;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * #53: the job's DEFAULT-LENIENT gating — nothing runs unless enabled, and each data class is
 * skipped until its window is a positive number of days; the dry-run flag is propagated.
 */
class RetentionPurgeJobTest {

    private final RetentionService service = mock(RetentionService.class);
    private final RetentionPurgeJob job = new RetentionPurgeJob(service);

    private void configure(boolean enabled, boolean dryRun, int email, int instances, int audit, int verif) {
        ReflectionTestUtils.setField(job, "enabled", enabled);
        ReflectionTestUtils.setField(job, "dryRun", dryRun);
        ReflectionTestUtils.setField(job, "emailMessagesDays", email);
        ReflectionTestUtils.setField(job, "formInstancesDays", instances);
        ReflectionTestUtils.setField(job, "formAuditDays", audit);
        ReflectionTestUtils.setField(job, "emailVerificationsDays", verif);
    }

    @Test
    void disabledDoesNothingEvenWithWindowsSet() {
        configure(false, true, 30, 30, 30, 30);
        job.run();
        verifyNoInteractions(service);
    }

    @Test
    void enabledRunsOnlyDataClassesWithAPositiveWindow() {
        configure(true, false, 365, 0, 0, 30);
        job.run();
        verify(service).purgeEmailMessages(any(), eq(false));
        verify(service).redactVerifications(any(), eq(false));
        verify(service, never()).anonymizeFormInstances(any(), anyBoolean());
        verify(service, never()).purgeFormAuditEvents(any(), anyBoolean());
    }

    @Test
    void dryRunFlagIsPropagated() {
        configure(true, true, 30, 30, 30, 30);
        when(service.purgeEmailMessages(any(), eq(true))).thenReturn(5L);
        job.run();
        verify(service).purgeEmailMessages(any(), eq(true));
        verify(service).anonymizeFormInstances(any(), eq(true));
        verify(service).purgeFormAuditEvents(any(), eq(true));
        verify(service).redactVerifications(any(), eq(true));
    }
}
