package com.vivek.ticketreservation.booking.application;

/** The payment intent could not be created; the hold was released and the booking failed. Retryable. */
public class PaymentUnavailableException extends RuntimeException {
    public PaymentUnavailableException(String message, Throwable cause) { super(message, cause); }
}
