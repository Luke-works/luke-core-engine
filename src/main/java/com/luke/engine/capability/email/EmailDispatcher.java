package com.luke.engine.capability.email;

import com.luke.engine.capability.email.PostmarkClient.SendResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers a persisted QUEUED {@link EmailMessage} to Postmark <b>off the request thread</b>, with
 * retry-and-backoff, and records the outcome on the row plus Micrometer metrics (#59).
 *
 * <p>The async path is an {@link TransactionalEventListener AFTER_COMMIT} {@link Async} listener, so
 * delivery starts only once the QUEUED row is durably committed (and never blocks the caller). The
 * same {@link #deliver} method backs the synchronous OTP path, where the caller needs the terminal
 * status inline.
 *
 * <p>Retry is bounded and only fires for TRANSIENT failures ({@link SendResult#retryable()} — network
 * error / Postmark 5xx); a business rejection is terminal, so we never hammer Postmark or risk
 * double-sending something it already accepted.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final PostmarkClient postmark;
    private final EmailMessageRepository repository;
    private final MeterRegistry metrics;

    @Value("${luke.email.max-attempts:3}")
    private int maxAttempts;

    @Value("${luke.email.retry-backoff-ms:500}")
    private long backoffMs;

    public EmailDispatcher(PostmarkClient postmark, EmailMessageRepository repository, MeterRegistry metrics) {
        this.postmark = postmark;
        this.repository = repository;
        this.metrics = metrics;
    }

    /** Async, after-commit delivery of a queued row. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueued(EmailQueuedEvent e) {
        repository.findById(e.messageId()).ifPresentOrElse(
                msg -> deliver(msg, e.serverToken(), e.body(), e.template()),
                () -> log.warn("Queued email {} not found at dispatch time — skipping", e.messageId()));
    }

    /**
     * Send an already-persisted QUEUED row (with retry) and record the outcome on the row + metrics.
     * Returns the updated row. Backs both the async listener and the synchronous OTP path.
     */
    public EmailMessage deliver(EmailMessage msg, String serverToken, Map<String, Object> body, boolean template) {
        long start = System.nanoTime();
        SendResult res = sendWithRetry(serverToken, body, template);
        apply(msg, res);
        EmailMessage saved = repository.save(msg);
        recordMetrics(res, start);
        return saved;
    }

    private SendResult sendWithRetry(String serverToken, Map<String, Object> body, boolean template) {
        int attempts = Math.max(1, maxAttempts);
        SendResult res = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            res = template ? postmark.sendTemplate(serverToken, body) : postmark.send(serverToken, body);
            if (res.ok() || !res.retryable() || attempt >= attempts) {
                return res;
            }
            long wait = backoffMs * (1L << (attempt - 1)); // 500ms, 1s, 2s, …
            log.warn("Postmark send attempt {}/{} failed (retryable): {} — retrying in {}ms",
                    attempt, attempts, res.message(), wait);
            sleep(wait);
        }
        return res;
    }

    private void apply(EmailMessage msg, SendResult res) {
        if (res.ok()) {
            msg.setStatus(EmailStatus.SENT);
            msg.setPostmarkMessageId(res.messageId());
            msg.setErrorCode(0);
            msg.setSentAt(LocalDateTime.now());
        } else {
            msg.setStatus(EmailStatus.FAILED);
            msg.setErrorCode(res.errorCode());
            msg.setErrorMessage(res.message());
        }
    }

    private void recordMetrics(SendResult res, long startNanos) {
        metrics.counter("luke.email.send", "outcome", res.ok() ? "sent" : "failed").increment();
        metrics.timer("luke.email.send.latency").record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
