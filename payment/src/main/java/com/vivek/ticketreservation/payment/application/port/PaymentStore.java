package com.vivek.ticketreservation.payment.application.port;

import com.vivek.ticketreservation.payment.domain.Payment;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of the payment aggregate (Repository, docs/lld/DESIGN_PATTERNS.md#2). */
public interface PaymentStore {

    /** Inserts the payment unless the booking already has one. @return the booking's payment either way */
    Payment insertIfAbsent(Payment payment);

    Optional<Payment> find(UUID paymentId);

    Optional<Payment> findByBooking(UUID bookingId);

    Optional<Payment> findByOrder(String gatewayOrderId);

    List<Payment> findByBookings(Collection<UUID> bookingIds);

    /** Writes status and failure reason if the row still has {@code payment.version()}. @return false on conflict */
    boolean update(Payment payment);

    /** Payments in PROCESSING or UNKNOWN not changed for {@code olderThan}: reconciliation candidates (P4.8). */
    List<Payment> findUnresolved(Duration olderThan, int limit);
}
