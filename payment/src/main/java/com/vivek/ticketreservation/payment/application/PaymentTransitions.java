package com.vivek.ticketreservation.payment.application;

import com.vivek.ticketreservation.payment.api.PaymentCaptured;
import com.vivek.ticketreservation.payment.api.PaymentFailed;
import com.vivek.ticketreservation.payment.api.PaymentNotFoundException;
import com.vivek.ticketreservation.payment.application.port.PaymentEventPublisher;
import com.vivek.ticketreservation.payment.application.port.PaymentStore;
import com.vivek.ticketreservation.payment.domain.Payment;
import com.vivek.ticketreservation.payment.domain.PaymentStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * The one place a payment's status is changed: load, apply the domain transition, write with an
 * optimistic-lock check (retrying on conflict), then announce CAPTURED / FAILED.
 *
 * <p>Checkout, webhooks (P4.3) and reconciliation (P4.8) can report the same outcome concurrently.
 * Only the caller whose write actually changed the status publishes, so each outcome is announced
 * once even if three paths learn it at the same moment.
 */
@Component
public class PaymentTransitions {
    private static final int MAX_ATTEMPTS = 5;
    private final PaymentStore store;
    private final PaymentEventPublisher events;

    PaymentTransitions(PaymentStore store, PaymentEventPublisher events) {
        this.store = store;
        this.events = events;
    }

    /** Result of {@link #apply}: the payment as stored afterwards, and whether this call changed it. */
    public record Applied(Payment payment, boolean changed) { }

    /**
     * Applies {@code change} (a domain method returning whether the state changed). Domain exceptions
     * (illegal transitions) propagate; callers that must tolerate stale events check {@code canMoveTo} inside.
     */
    public Applied apply(UUID paymentId, Predicate<Payment> change) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Payment payment = store.find(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
            if (!change.test(payment)) return new Applied(payment, false);
            if (store.update(payment)) {
                announce(payment);
                return new Applied(store.find(paymentId).orElseThrow(), true);
            }
        }
        throw new IllegalStateException("Payment " + paymentId + " kept changing; giving up after " + MAX_ATTEMPTS + " attempts");
    }

    private void announce(Payment payment) {
        if (payment.status() == PaymentStatus.CAPTURED) events.publish(new PaymentCaptured(payment.id(), payment.bookingId()));
        if (payment.status() == PaymentStatus.FAILED) events.publish(new PaymentFailed(payment.id(), payment.bookingId(), payment.failureReason()));
    }
}
