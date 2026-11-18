package com.vivek.ticketreservation.payment.application;

import com.vivek.ticketreservation.payment.api.PaymentState;
import com.vivek.ticketreservation.payment.api.PaymentView;
import com.vivek.ticketreservation.payment.domain.Payment;

/** Maps the aggregate to the published view, in one place. */
public final class PaymentViews {
    private PaymentViews() { }

    public static PaymentView of(Payment p) {
        return new PaymentView(p.id(), p.bookingId(), PaymentState.valueOf(p.status().name()), p.amountMinor(),
                p.currency(), p.gateway(), p.gatewayOrderId(), p.failureReason());
    }
}
