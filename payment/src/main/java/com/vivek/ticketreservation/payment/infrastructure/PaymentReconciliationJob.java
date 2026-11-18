package com.vivek.ticketreservation.payment.infrastructure;

import com.vivek.ticketreservation.payment.application.ReconcilePaymentsUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Runs payment reconciliation on a schedule (P4.8): <i>when</i> lives here, <i>what</i> in
 * {@link ReconcilePaymentsUseCase}. Idempotent, so every replica may run it. The age threshold must be
 * longer than the gateway call timeout, so a checkout still waiting for its answer is left alone.
 */
@Component
@EnableScheduling
class PaymentReconciliationJob {
    private final ReconcilePaymentsUseCase reconcile;
    private final Duration olderThan;

    PaymentReconciliationJob(ReconcilePaymentsUseCase reconcile,
                             @Value("${payment.reconcile.older-than:30s}") Duration olderThan) {
        this.reconcile = reconcile;
        this.olderThan = olderThan;
    }

    @Scheduled(fixedDelayString = "${payment.reconcile.interval:10s}", initialDelayString = "${payment.reconcile.initial-delay:10s}")
    void run() { reconcile.reconcile(olderThan, 100); }
}
