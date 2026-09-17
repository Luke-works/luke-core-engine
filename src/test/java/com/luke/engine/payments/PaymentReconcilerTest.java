package com.luke.engine.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The reconciler never runs on the caller's (the shared scheduler's) thread, and never twice at once. */
class PaymentReconcilerTest {

    private final PaymentsProperties props = new PaymentsProperties("sk_test_x", "pk_test_x", "ca_x", "", "",
            "https://js.stripe.com/v3/", "", "", 120);
    private final FormPaymentService payments = mock(FormPaymentService.class);
    private final PaymentConnectStateRepository states = mock(PaymentConnectStateRepository.class);
    private final PaymentWebhookEventRepository events = mock(PaymentWebhookEventRepository.class);

    @Test
    void aTickHandsTheRunToItsOwnThread_andSkipsWhileOneIsRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        Thread[] ranOn = new Thread[1];
        when(payments.reconcileStale(any(Duration.class))).thenAnswer(inv -> {
            ranOn[0] = Thread.currentThread();
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 0;
        });
        PaymentReconciler r = new PaymentReconciler(props, payments, states, events, true, 60_000);
        r.run(); // returns at once
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ranOn[0]).isNotSameAs(caller);
        assertThat(ranOn[0].getName()).isEqualTo("payment-reconciler");

        r.run(); // still running: skipped, not queued
        release.countDown();
        verify(payments, timeout(5_000).times(1)).reconcileStale(Duration.ofMillis(60_000));
        verify(events, timeout(5_000)).deleteOlderThan(any());
        r.stop();
    }

    @Test
    void disabledOrUnconfiguredDoesNothing() {
        new PaymentReconciler(props, payments, states, events, false, 60_000).run();
        PaymentsProperties off = new PaymentsProperties("", "", "", "", "", "", "", "", 120);
        new PaymentReconciler(off, payments, states, events, true, 60_000).run();
        verify(payments, never()).reconcileStale(any(Duration.class));
    }
}
