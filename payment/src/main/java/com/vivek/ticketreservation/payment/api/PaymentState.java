package com.vivek.ticketreservation.payment.api;

/** Published payment status (schema PaymentStatus); mirrors the internal state machine without exposing it. */
public enum PaymentState { INITIATED, PROCESSING, CAPTURED, FAILED, UNKNOWN, REFUNDED, CANCELLED }
