package com.vivek.ticketreservation.booking.domain;

/**
 * Where the booking saga is (P4.4). The forward path is {@code AWAITING_PAYMENT → CONFIRMING_SEATS →
 * DONE}; compensations are {@code RELEASING_SEATS} (payment failed) and {@code REFUNDING} (money arrived
 * but the seats could not be confirmed: late payment, or payment after a cancel).
 */
public enum SagaStep { AWAITING_PAYMENT, CONFIRMING_SEATS, RELEASING_SEATS, REFUNDING, DONE }
