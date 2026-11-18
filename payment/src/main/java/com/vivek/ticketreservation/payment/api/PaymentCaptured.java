package com.vivek.ticketreservation.payment.api;

import java.util.UUID;

/** The money was taken. Published once per payment, after the change is committed. */
public record PaymentCaptured(UUID paymentId, UUID bookingId) { }
