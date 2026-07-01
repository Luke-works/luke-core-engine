package com.luke.engine.workflow.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link IntegrationEventOutboxConsumer} (WF-12): the QUEUED → SENT/SKIPPED/FAILED
 * transitions, with the correlator mocked (no engine needed).
 */
class IntegrationEventOutboxConsumerTest {

    private final IntegrationEventOutboxRepository outbox = mock(IntegrationEventOutboxRepository.class);
    private final IntegrationEventCorrelator correlator = mock(IntegrationEventCorrelator.class);
    private final IntegrationEventOutboxConsumer consumer = new IntegrationEventOutboxConsumer(outbox, correlator);

    private IntegrationEventOutbox queued() {
        return new IntegrationEventOutbox("t1", "integrations.Opportunity", null, "conn1", "sync", "{}");
    }

    @Test
    void marksSentWhenCorrelationHitsAWaitingWorkflow() {
        IntegrationEventOutbox row = queued();
        when(correlator.correlate("t1", "integrations.Opportunity", null, "{}")).thenReturn(2);

        consumer.deliver(row);

        assertThat(row.getState()).isEqualTo(IntegrationEventOutbox.SENT);
        verify(outbox).save(row);
    }

    @Test
    void marksSkippedWhenNothingIsWaiting() {
        IntegrationEventOutbox row = queued();
        when(correlator.correlate(anyString(), anyString(), any(), anyString())).thenReturn(0);

        consumer.deliver(row);

        assertThat(row.getState()).isEqualTo(IntegrationEventOutbox.SKIPPED);
    }

    @Test
    void keepsQueuedAndIncrementsRetryOnTransientError() {
        // A transient error leaves the row QUEUED so the next poll retries it (mirrors the phone
        // consumer's keep-and-retry), recording the error + a bumped retry count.
        IntegrationEventOutbox row = queued();
        when(correlator.correlate(anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        consumer.deliver(row);

        assertThat(row.getState()).isEqualTo(IntegrationEventOutbox.QUEUED);
        assertThat(row.getRetryCount()).isEqualTo(1);
        assertThat(row.getErrorMessage()).contains("db down");
    }

    @Test
    void drainDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(consumer, "enabled", false);

        consumer.drain();

        verify(outbox, never()).findByStateOrderByCreatedAtAsc(anyString());
        verify(correlator, never()).correlate(anyString(), anyString(), any(), anyString());
    }

    @Test
    void drainProcessesQueuedRows() {
        ReflectionTestUtils.setField(consumer, "enabled", true);
        when(outbox.findByStateOrderByCreatedAtAsc(IntegrationEventOutbox.QUEUED))
                .thenReturn(List.of(queued()));
        when(correlator.correlate(anyString(), anyString(), any(), anyString())).thenReturn(1);

        consumer.drain();

        verify(correlator).correlate(anyString(), anyString(), any(), anyString());
    }
}
