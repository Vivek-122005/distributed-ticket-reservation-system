package com.vivek.ticketreservation.booking.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What booking needs from the payment module, in booking's words (P4.4). Adapter:
 * {@code infrastructure.PaymentModulePayments} over {@code payment.api.PaymentApi}.
 */
public interface Payments {

    enum Status { INITIATED, PROCESSING, CAPTURED, FAILED, UNKNOWN, REFUNDED, CANCELLED }

    record PaymentInfo(UUID paymentId, Status status, String gateway, String gatewayOrderId) { }

    /** Idempotent: one intent per booking. */
    PaymentInfo createIntent(UUID bookingId, UUID userId, long amountMinor, String currency);

    Optional<PaymentInfo> find(UUID bookingId);

    Map<UUID, PaymentInfo> findAll(Collection<UUID> bookingIds);

    /** @return the status after trying: CANCELLED, or the in-flight / paid status that prevented it */
    Status cancel(UUID bookingId);

    /** Refunds a captured payment. Idempotent. */
    void refund(UUID bookingId);
}
