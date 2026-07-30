package com.luke.engine.capability.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link AccessRequestOutboxConsumer} — specifically the guards that stop it
 * starting an approval process it shouldn't.
 *
 * <p>The "already decided" case is the subtle one: if the engine was unavailable when an owner
 * acted, the controller decides the request inline, and a later drain would otherwise put an
 * approval task on a request that has already been granted.
 */
class AccessRequestOutboxConsumerTest {

    private static final String TENANT = "TEN-ACM-01JAN26";
    private static final String REQ_ID = "req-1";

    private AccessRequestOutboxRepository outbox;
    private AccessRequestRepository requests;
    private AccessApprovalProcessService processService;
    private AccessRequestOutboxConsumer consumer;
    private AccessRequestOutbox row;

    @BeforeEach
    void setUp() {
        outbox = mock(AccessRequestOutboxRepository.class);
        requests = mock(AccessRequestRepository.class);
        processService = mock(AccessApprovalProcessService.class);
        consumer = new AccessRequestOutboxConsumer(outbox, requests, processService);
        ReflectionTestUtils.setField(consumer, "enabled", true);
        ReflectionTestUtils.setField(consumer, "maxRetries", 3);

        when(outbox.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
        row = new AccessRequestOutbox(TENANT, REQ_ID, AccessApprovalProcessService.businessKey(REQ_ID));
    }

    private AccessRequest request(String status) {
        AccessRequest req = new AccessRequest(TENANT, "workos:user_member", "FORMS", "read");
        req.setId(REQ_ID);
        req.setStatus(status);
        when(requests.findById(REQ_ID)).thenReturn(Optional.of(req));
        return req;
    }

    @Test
    void startsTheProcessForAPendingRequestAndRecordsTheInstance() {
        AccessRequest req = request(AccessRequest.PENDING);
        when(processService.start(req)).thenReturn("proc-1");

        consumer.process(row);

        assertThat(row.getStatus()).isEqualTo(AccessRequestOutbox.PUBLISHED);
        assertThat(row.getProcessInstanceId()).isEqualTo("proc-1");
        // The request must carry the instance, or approve/deny can't find its task.
        assertThat(req.getProcessInstanceId()).isEqualTo("proc-1");
    }

    @Test
    void doesNotStartAProcessForARequestDecidedBeforeTheDrain() {
        for (String decided : new String[] {
                AccessRequest.APPROVED, AccessRequest.DENIED, AccessRequest.CANCELLED}) {
            AccessRequestOutbox fresh =
                    new AccessRequestOutbox(TENANT, REQ_ID, AccessApprovalProcessService.businessKey(REQ_ID));
            request(decided);

            consumer.process(fresh);

            assertThat(fresh.getStatus()).as("%s row", decided).isEqualTo(AccessRequestOutbox.PUBLISHED);
            assertThat(fresh.getProcessInstanceId()).as("%s instance", decided).isNull();
        }
        verify(processService, never()).start(any());
    }

    @Test
    void aVanishedRequestFailsTheRowRatherThanRetryingForever() {
        when(requests.findById(REQ_ID)).thenReturn(Optional.empty());

        consumer.process(row);

        assertThat(row.getStatus()).isEqualTo(AccessRequestOutbox.FAILED);
        verify(processService, never()).start(any());
    }

    @Test
    void aTransientFailureStaysQueuedUntilTheRetryBudgetIsSpent() {
        AccessRequest req = request(AccessRequest.PENDING);
        when(processService.start(req)).thenThrow(new RuntimeException("engine down"));

        consumer.process(row);
        assertThat(row.getStatus()).isEqualTo(AccessRequestOutbox.QUEUED);
        assertThat(row.getRetryCount()).isEqualTo(1);

        consumer.process(row);
        consumer.process(row);
        assertThat(row.getStatus()).isEqualTo(AccessRequestOutbox.FAILED);
        assertThat(row.getErrorMessage()).contains("engine down");
    }
}
