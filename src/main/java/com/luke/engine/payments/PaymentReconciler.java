package com.luke.engine.payments;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The backstop for everything the webhook and the payer's page might miss: a charge nobody finished,
 * a webhook that never arrived, a payer who closed the tab mid-3-D-Secure. Every open charge untouched
 * for the TTL is re-read from Stripe and settled — or cancelled at Stripe and its submission released.
 *
 * <p>Runs on every node unless {@code luke.payments.reconcile-enabled=false}; that is safe because
 * settlement takes the payment row's lock and Stripe cancellation is idempotent. Turn it off on all
 * but one node to save API calls.
 *
 * <p><b>Off the shared scheduler.</b> Spring's scheduler has one thread, which the submission outbox
 * and every other poller share; a slow Stripe must never stall them. The tick only hands the run to
 * this component's own thread (skipping it if the previous run is still going), and each run stops at
 * {@code luke.payments.reconcile-budget-ms}, leaving the rest for the next.
 */
@Component
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    private final PaymentsProperties props;
    private final FormPaymentService payments;
    private final PaymentConnectStateRepository states;
    private final PaymentWebhookEventRepository events;
    private final boolean enabled;
    private final Duration budget;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "payment-reconciler");
        t.setDaemon(true);
        return t;
    });

    public PaymentReconciler(PaymentsProperties props, FormPaymentService payments, PaymentConnectStateRepository states,
                             PaymentWebhookEventRepository events,
                             @Value("${luke.payments.reconcile-enabled:true}") boolean enabled,
                             @Value("${luke.payments.reconcile-budget-ms:120000}") long budgetMs) {
        this.props = props;
        this.payments = payments;
        this.states = states;
        this.events = events;
        this.enabled = enabled;
        this.budget = Duration.ofMillis(Math.max(1_000, budgetMs));
    }

    @Scheduled(fixedDelayString = "${luke.payments.reconcile-ms:300000}", initialDelayString = "${luke.payments.reconcile-ms:300000}")
    public void run() {
        if (!enabled || !props.enabled()) return;
        if (!running.compareAndSet(false, true)) {
            log.info("Payment reconciliation still running from the previous tick; skipping this one");
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    runOnce();
                } catch (RuntimeException e) {
                    log.warn("Payment reconciliation run failed: {}", e.getMessage());
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            running.set(false);
        }
    }

    /** One synchronous pass — what the worker thread runs. */
    void runOnce() {
        int handled = payments.reconcileStale(budget);
        if (handled > 0) log.info("Payment reconciliation: settled or released {} stale charge(s)", handled);
        prune();
    }

    void prune() {
        states.deleteExpired(LocalDateTime.now());
        events.deleteOlderThan(LocalDateTime.now().minusDays(7));
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
