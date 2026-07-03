package com.luke.engine.capability.form;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/** #54: instance expiry — isExpired() semantics + the sweep marks lapsed instances EXPIRED. */
class FormInstanceExpiryTest {

    private FormInstance instance(LocalDateTime expiresAt, String state) {
        FormInstance i = new FormInstance();
        i.setExpiresAt(expiresAt);
        i.setState(state);
        return i;
    }

    @Test
    void isExpiredReflectsTheDeadline() {
        assertTrue(instance(LocalDateTime.now().minusMinutes(1), FormInstanceStates.OPENED).isExpired());
        assertFalse(instance(LocalDateTime.now().plusMinutes(1), FormInstanceStates.OPENED).isExpired());
        assertFalse(instance(null, FormInstanceStates.OPENED).isExpired()); // no expiry set
    }

    @Test
    void sweepMarksLapsedInstancesExpired() {
        FormInstanceRepository repo = mock(FormInstanceRepository.class);
        FormInstance lapsed = instance(LocalDateTime.now().minusHours(1), FormInstanceStates.IN_PROGRESS);
        when(repo.findByStateInAndExpiresAtBefore(eq(FormInstanceStates.OPEN), any(), any(Limit.class)))
                .thenReturn(List.of(lapsed));

        new FormInstanceExpirySweeper(repo).sweep();

        assertTrue(FormInstanceStates.EXPIRED.equals(lapsed.getState()));
        verify(repo).saveAll(List.of(lapsed));
    }

    @Test
    void sweepNoOpsWhenNothingExpired() {
        FormInstanceRepository repo = mock(FormInstanceRepository.class);
        when(repo.findByStateInAndExpiresAtBefore(any(), any(), any(Limit.class))).thenReturn(List.of());
        new FormInstanceExpirySweeper(repo).sweep();
        verify(repo, never()).saveAll(any());
    }
}
