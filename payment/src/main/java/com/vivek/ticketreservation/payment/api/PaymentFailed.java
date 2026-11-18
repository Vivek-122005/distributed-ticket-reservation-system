package com.vivek.ticketreservation.payment.api;

import java.util.UUID;

/** The gateway declined, or reconciliation found that no money moved. Published once, after commit. */
public record PaymentFailed(UUID paymentId, UUID bookingId, String reason) { }
