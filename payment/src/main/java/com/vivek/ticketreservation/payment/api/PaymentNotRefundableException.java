package com.vivek.ticketreservation.payment.api;

/** Refund asked for a payment that has not been captured (yet). */
public class PaymentNotRefundableException extends RuntimeException {
    public PaymentNotRefundableException(String message) { super(message); }
}
