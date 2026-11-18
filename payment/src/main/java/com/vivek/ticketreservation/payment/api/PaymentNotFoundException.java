package com.vivek.ticketreservation.payment.api;

/** No such payment (for this user). */
public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(Object id) { super("Payment " + id + " not found"); }
}
