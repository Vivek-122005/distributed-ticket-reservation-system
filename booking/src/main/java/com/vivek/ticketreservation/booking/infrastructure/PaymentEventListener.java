package com.vivek.ticketreservation.booking.infrastructure;

import com.vivek.ticketreservation.booking.application.BookingSaga;
import com.vivek.ticketreservation.payment.api.PaymentCaptured;
import com.vivek.ticketreservation.payment.api.PaymentFailed;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Feeds payment outcomes into the saga (Observer, DESIGN_PATTERNS.md#14). Payment publishes these after
 * its change is durable and never calls booking, so the module graph stays acyclic. If an event is
 * lost (crash), the saga's periodic payment check finds the outcome anyway (P4.6).
 */
@Component
class PaymentEventListener {
    private final BookingSaga saga;

    PaymentEventListener(BookingSaga saga) { this.saga = saga; }

    @EventListener
    void on(PaymentCaptured event) { saga.onPaymentCaptured(event.bookingId()); }

    @EventListener
    void on(PaymentFailed event) { saga.onPaymentFailed(event.bookingId()); }
}
