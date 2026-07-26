package com.luke.engine.capability.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luke.engine.document.DocumentCamundaMirror;
import com.luke.engine.form.InternalProcessService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A TRANSIENT start failure must NOT terminally strand an MVP form intake. The row stays QUEUED
 * (so the next poll retries it) until the retry budget is exhausted; only then is it FAILED.
 * Previously any exception set FAILED on the first miss and drain() only re-picks QUEUED rows, so a
 * single Camunda/DB blip permanently stranded the submission.
 */
class FormSubmissionOutboxRetryTest {

    private final FormSubmissionOutboxRepository outbox = mock(FormSubmissionOutboxRepository.class);
    private final FormInstanceRepository instances = mock(FormInstanceRepository.class);
    private final InternalProcessService processService = mock(InternalProcessService.class);
    private final DocumentCamundaMirror documentMirror = mock(DocumentCamundaMirror.class);
    private final FormSubmissionPdfService submissionPdf = mock(FormSubmissionPdfService.class);

    private FormSubmissionOutboxConsumer consumer(int maxRetries) {
        FormSubmissionOutboxConsumer c = new FormSubmissionOutboxConsumer(
                outbox, instances, processService, documentMirror, submissionPdf);
        ReflectionTestUtils.setField(c, "maxRetries", maxRetries);
        return c;
    }

    private FormSubmissionOutbox queuedRow() {
        FormSubmissionOutbox row = new FormSubmissionOutbox();
        row.setStatus("QUEUED");
        row.setTenantId("t-1");
        row.setBusinessKey("inst-1");
        row.setFormInstanceId("inst-1");
        return row;
    }

    @Test
    void transientFailure_keepsRowQueuedAndIncrementsRetry() {
        when(processService.start(any(), any(), any())).thenThrow(new RuntimeException("db blip"));
        FormSubmissionOutbox row = queuedRow();

        consumer(3).process(row);

        assertEquals("QUEUED", row.getStatus(), "a transient failure must leave the row drainable");
        assertEquals(1, row.getRetryCount());
    }

    @Test
    void exhaustedRetryBudget_marksRowFailedTerminally() {
        when(processService.start(any(), any(), any())).thenThrow(new RuntimeException("boom"));
        when(instances.findById(anyString())).thenReturn(Optional.empty());
        FormSubmissionOutboxConsumer c = consumer(2);
        FormSubmissionOutbox row = queuedRow();

        c.process(row); // attempt 1 → still QUEUED (retryable)
        assertEquals("QUEUED", row.getStatus());
        assertEquals(1, row.getRetryCount());

        c.process(row); // attempt 2 → hits the cap → terminal FAILED
        assertEquals("FAILED", row.getStatus());
        assertEquals(2, row.getRetryCount());
    }
}
