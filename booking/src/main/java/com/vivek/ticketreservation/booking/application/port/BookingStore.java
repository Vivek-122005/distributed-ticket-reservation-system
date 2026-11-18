package com.vivek.ticketreservation.booking.application.port;

import com.vivek.ticketreservation.booking.domain.Booking;
import com.vivek.ticketreservation.booking.domain.BookingStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of the booking aggregate (Repository, docs/lld/DESIGN_PATTERNS.md#2). */
public interface BookingStore {

    /** Inserts a new booking with its seat lines; returns it as stored (with createdAt). */
    Booking insert(Booking booking);

    Optional<Booking> find(UUID bookingId);

    /**
     * Writes the booking's status if the row still has {@code booking.version()} (optimistic lock).
     * @return false if someone else changed it first; the caller reloads and decides again
     */
    boolean updateStatus(Booking booking);

    /** A user's bookings, newest first; {@code status} null means any (P3.8). */
    List<Booking> findByUser(UUID userId, BookingStatus status, int offset, int limit);

    long countByUser(UUID userId, BookingStatus status);
}
