package com.luke.engine.capability.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.email.PostmarkClient.SendResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * #59: the delivery engine's retry-with-backoff. Transient (retryable) failures are retried up to the
 * cap; a permanent failure (business rejection) is not; the row is flipped to SENT/FAILED accordingly.
 */
class EmailDispatcherTest {

    private final PostmarkClient postmark = mock(PostmarkClient.class);
    private final EmailMessageRepository repo = mock(EmailMessageRepository.class);
    private final MeterRegistry metrics = new SimpleMeterRegistry();
    private final EmailDispatcher dispatcher = new EmailDispatcher(postmark, repo, metrics);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(dispatcher, "maxAttempts", 3);
        ReflectionTestUtils.setField(dispatcher, "backoffMs", 1L); // keep the test fast
        when(repo.save(any())).thenAnswer(a -> a.getArgument(0));
    }

    private static SendResult transient_(String msg) {
        return new SendResult(false, null, null, msg, true);
    }

    private static SendResult permanent(int code, String msg) {
        return new SendResult(false, null, code, msg, false);
    }

    private static SendResult ok(String id) {
        return new SendResult(true, id, 0, "OK", false);
    }

    @Test
    void retriesATransientFailureThenSucceeds() {
        when(postmark.send(any(), any()))
                .thenReturn(transient_("connection refused"))
                .thenReturn(ok("pm-1"));
        EmailMessage msg = new EmailMessage();

        dispatcher.deliver(msg, "tok", Map.of(), false);

        verify(postmark, times(2)).send(any(), any());
        assertThat(msg.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(msg.getPostmarkMessageId()).isEqualTo("pm-1");
    }

    @Test
    void doesNotRetryAPermanentFailure() {
        when(postmark.send(any(), any())).thenReturn(permanent(406, "Inactive recipient"));
        EmailMessage msg = new EmailMessage();

        dispatcher.deliver(msg, "tok", Map.of(), false);

        verify(postmark, times(1)).send(any(), any());
        assertThat(msg.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(msg.getErrorCode()).isEqualTo(406);
    }

    @Test
    void stopsAfterTheMaxAttemptsOnPersistentTransientFailure() {
        when(postmark.send(any(), any())).thenReturn(transient_("timeout"));
        EmailMessage msg = new EmailMessage();

        dispatcher.deliver(msg, "tok", Map.of(), false);

        verify(postmark, times(3)).send(any(), any()); // maxAttempts
        assertThat(msg.getStatus()).isEqualTo(EmailStatus.FAILED);
    }

    @Test
    void templateSendsUseTheTemplateEndpoint() {
        when(postmark.sendTemplate(any(), any())).thenReturn(ok("pm-2"));
        EmailMessage msg = new EmailMessage();

        dispatcher.deliver(msg, "tok", Map.of(), true);

        verify(postmark).sendTemplate(any(), any());
        assertThat(msg.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(metrics.counter("luke.email.send", "outcome", "sent").count()).isEqualTo(1.0);
    }
}
