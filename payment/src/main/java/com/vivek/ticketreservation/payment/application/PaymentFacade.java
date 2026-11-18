package com.vivek.ticketreservation.payment.application;

import com.vivek.ticketreservation.payment.api.PaymentApi;
import com.vivek.ticketreservation.payment.api.PaymentNotFoundException;
import com.vivek.ticketreservation.payment.api.PaymentNotRefundableException;
import com.vivek.ticketreservation.payment.api.PaymentView;
import com.vivek.ticketreservation.payment.application.port.PaymentGateway;
import com.vivek.ticketreservation.payment.application.port.PaymentStore;
import com.vivek.ticketreservation.payment.domain.Payment;
import com.vivek.ticketreservation.payment.domain.PaymentStatus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Entry point of the payment module for other modules (Facade, docs/lld/DESIGN_PATTERNS.md#9-facade).
 *
 * <p>Double-charge protection (N2) has two layers: one payment per booking ({@code UNIQUE booking_id},
 * {@code insertIfAbsent}), and the gateway order is created with the booking id as idempotency key,
 * so even a retried intent creation maps to the same provider order.
 */
@Component
class PaymentFacade implements PaymentApi {
    private final PaymentStore store;
    private final PaymentGateway gateway;
    private final PaymentTransitions transitions;

    PaymentFacade(PaymentStore store, PaymentGateway gateway, PaymentTransitions transitions) {
        this.store = store;
        this.gateway = gateway;
        this.transitions = transitions;
    }

    @Override
    public PaymentView createIntent(UUID bookingId, UUID userId, long amountMinor, String currency) {
        Optional<Payment> existing = store.findByBooking(bookingId);
        if (existing.isPresent()) return PaymentViews.of(existing.get());
        String orderId = gateway.createOrder(bookingId.toString(), amountMinor, currency);
        return PaymentViews.of(store.insertIfAbsent(
                Payment.initiated(UUID.randomUUID(), bookingId, userId, amountMinor, currency, gateway.name(), orderId)));
    }

    @Override
    public Optional<PaymentView> findByBooking(UUID bookingId) { return store.findByBooking(bookingId).map(PaymentViews::of); }

    @Override
    public List<PaymentView> findByBookings(Collection<UUID> bookingIds) {
        return bookingIds.isEmpty() ? List.of() : store.findByBookings(bookingIds).stream().map(PaymentViews::of).toList();
    }

    @Override
    public PaymentView cancel(UUID bookingId) {
        Payment payment = store.findByBooking(bookingId).orElseThrow(() -> new PaymentNotFoundException("of booking " + bookingId));
        return PaymentViews.of(transitions.apply(payment.id(),
                p -> p.status().canTransitionTo(PaymentStatus.CANCELLED) && p.cancel()).payment());
    }

    @Override
    public PaymentView refund(UUID bookingId) {
        Payment payment = store.findByBooking(bookingId).orElseThrow(() -> new PaymentNotFoundException("of booking " + bookingId));
        if (payment.status() == PaymentStatus.REFUNDED) return PaymentViews.of(payment);
        if (payment.status() != PaymentStatus.CAPTURED) {
            throw new PaymentNotRefundableException("Payment " + payment.id() + " is " + payment.status() + ", not CAPTURED");
        }
        gateway.refund(payment.gatewayOrderId());   // idempotent at the provider; safe to repeat on retry
        return PaymentViews.of(transitions.apply(payment.id(), Payment::markRefunded).payment());
    }
}
