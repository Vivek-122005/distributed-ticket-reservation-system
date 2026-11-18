package com.vivek.ticketreservation.payment.application;

import com.vivek.ticketreservation.payment.application.port.PaymentGateway;
import com.vivek.ticketreservation.payment.application.port.PaymentGateway.OrderStatus;
import com.vivek.ticketreservation.payment.application.port.PaymentStore;
import com.vivek.ticketreservation.payment.domain.Payment;
import com.vivek.ticketreservation.payment.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves payments whose outcome we don't know (P4.8): {@code UNKNOWN} (the gateway did not answer)
 * and {@code PROCESSING} left behind by a crash during the gateway call.
 *
 * <p>It only <b>asks</b> the provider ({@code fetchStatus}, by order id) and records the answer; it
 * never charges or refunds by itself (SRP). The answer goes through {@link PaymentTransitions}, so
 * {@code PaymentCaptured} / {@code PaymentFailed} reach the saga exactly as if checkout had seen them:
 * a capture on time confirms the booking, a capture after expiry is refunded. Only payments untouched
 * for {@code olderThan} are looked at, so an in-flight checkout is never raced.
 */
@Service
public class ReconcilePaymentsUseCase {
    private static final Logger log = LoggerFactory.getLogger(ReconcilePaymentsUseCase.class);

    public enum Outcome { CAPTURED, FAILED, STILL_UNKNOWN }

    private final PaymentStore store;
    private final PaymentGateway gateway;
    private final PaymentTransitions transitions;

    public ReconcilePaymentsUseCase(PaymentStore store, PaymentGateway gateway, PaymentTransitions transitions) {
        this.store = store;
        this.gateway = gateway;
        this.transitions = transitions;
    }

    public Map<Outcome, Integer> reconcile(Duration olderThan, int limit) {
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        List<Payment> unresolved = store.findUnresolved(olderThan, limit);
        for (Payment payment : unresolved) {
            Outcome outcome;
            try {
                outcome = resolve(payment);
            } catch (RuntimeException e) {
                log.warn("Could not reconcile payment {} (order {}); will retry", payment.id(), payment.gatewayOrderId(), e);
                outcome = Outcome.STILL_UNKNOWN;
            }
            counts.merge(outcome, 1, Integer::sum);
        }
        if (!unresolved.isEmpty()) log.info("Payment reconciliation: {}", counts);
        return counts;
    }

    private Outcome resolve(Payment payment) {
        OrderStatus status = gateway.fetchStatus(payment.gatewayOrderId());
        return switch (status) {
            case CAPTURED, REFUNDED -> {
                transitions.apply(payment.id(), p -> p.canMoveTo(PaymentStatus.CAPTURED) && p.markCaptured());
                yield Outcome.CAPTURED;
            }
            // The provider never charged it (or has no record of it): no money moved.
            case DECLINED, NOT_CHARGED, NOT_FOUND -> {
                String reason = status == OrderStatus.DECLINED ? "card_declined" : "not_charged";
                transitions.apply(payment.id(), p -> p.canMoveTo(PaymentStatus.FAILED) && p.markFailed(reason));
                yield Outcome.FAILED;
            }
        };
    }
}
