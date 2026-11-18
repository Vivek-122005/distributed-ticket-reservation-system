package com.vivek.ticketreservation.payment.domain;

/** A payment state change the state machine (plan §4.4b) does not allow; nothing was changed. */
public class IllegalPaymentTransitionException extends RuntimeException {
    private final PaymentStatus from;

    public IllegalPaymentTransitionException(Object paymentId, PaymentStatus from, PaymentStatus to) {
        super("Payment " + paymentId + " cannot go from " + from + " to " + to);
        this.from = from;
    }

    public PaymentStatus from() { return from; }
}
