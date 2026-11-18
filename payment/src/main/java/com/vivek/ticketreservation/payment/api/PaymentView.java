package com.vivek.ticketreservation.payment.api;

import java.util.UUID;

/** A payment as other modules see it (schema PaymentSummary). */
public record PaymentView(UUID paymentId, UUID bookingId, PaymentState status, long amountMinor, String currency,
                          String gateway, String gatewayOrderId, String failureReason) { }
