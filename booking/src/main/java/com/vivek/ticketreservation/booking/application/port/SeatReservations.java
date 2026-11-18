package com.vivek.ticketreservation.booking.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What booking needs from seat inventory, in booking's own words (P3.3).
 *
 * <p>Pattern: Ports & Adapters + Adapter (docs/lld/DESIGN_PATTERNS.md#8-adapter). Today
 * {@code infrastructure.InventorySeatReservations} adapts the in-process {@code InventoryApi}; after
 * extraction (P7.8) an HTTP adapter with timeouts and a circuit breaker implements the same port and
 * nothing in the use cases changes (DIP).
 */
public interface SeatReservations {

    /**
     * Holds every seat or none.
     * @throws SeatReservationException SEAT_UNAVAILABLE, SHOW_NOT_FOUND or SHOW_NOT_ON_SALE
     */
    Reservation hold(UUID showId, UUID userId, List<String> seatCodes);

    /** Seats become BOOKED. Idempotent. @throws SeatReservationException HOLD_EXPIRED (refund path) */
    void confirm(UUID holdId, UUID userId);

    /** Seats go back on sale. Idempotent; releasing an expired hold is a no-op. */
    void release(UUID holdId, UUID userId);

    record Reservation(UUID holdId, List<ReservedSeat> seats, String currency, Instant expiresAt) { }

    record ReservedSeat(String seatCode, long priceMinor) { }
}
