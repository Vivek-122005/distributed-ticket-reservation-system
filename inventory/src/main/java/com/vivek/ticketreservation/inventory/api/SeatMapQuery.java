package com.vivek.ticketreservation.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Seat map of a show with current availability (FR-02). Read-only and allowed to be slightly stale:
 * a stale "available" only causes a fast 409 on hold, never an oversell (the claim decides).
 *
 * <p>Port for the read path: today served from Postgres; P2.3 adds a Redis-backed implementation.
 */
public interface SeatMapQuery {

    /** @param section only seats of this section, or all if empty. @throws ShowNotFoundException if unknown */
    SeatMapView seatMap(UUID showId, Optional<String> section);

    record SeatMapView(UUID showId, Instant generatedAt, List<SeatView> seats) { }

    /** {@code status} is AVAILABLE, HELD or BOOKED; a seat whose hold expired is reported AVAILABLE. */
    record SeatView(UUID seatId, String seatCode, String section, int row, int col, String status, long priceMinor) { }
}
