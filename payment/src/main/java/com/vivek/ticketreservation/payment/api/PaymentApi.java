package com.vivek.ticketreservation.payment.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules (the booking saga) may do with payments. Checkout itself is the user's action
 * and is not here (ISP): it is reached over HTTP ({@code POST /payments/{id}/checkout}).
 *
 * <p>Pattern: Facade (docs/lld/DESIGN_PATTERNS.md#9-facade). Outcomes are announced as
 * {@link PaymentCaptured} / {@link PaymentFailed} events, never by calling booking (the module graph
 * stays acyclic).
 */
public interface PaymentApi {

    /** Creates the payment intent of a booking, or returns the existing one (one payment per booking). */
    PaymentView createIntent(UUID bookingId, UUID userId, long amountMinor, String currency);

    Optional<PaymentView> findByBooking(UUID bookingId);

    List<PaymentView> findByBookings(Collection<UUID> bookingIds);

    /**
     * Withdraws the intent if nobody has paid yet. Idempotent.
     * @return the payment after the call: {@code CANCELLED}, or the status that made it impossible
     *         (for example {@code PROCESSING}; a capture will then go to the refund path)
     */
    PaymentView cancel(UUID bookingId);

    /** Refunds a captured payment. Idempotent. @throws PaymentNotRefundableException unless CAPTURED or REFUNDED */
    PaymentView refund(UUID bookingId);
}
