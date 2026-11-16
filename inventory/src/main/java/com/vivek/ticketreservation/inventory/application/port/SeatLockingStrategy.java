package com.vivek.ticketreservation.inventory.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port: claims seats of a show for a hold. Implemented in {@code infrastructure}.
 *
 * <p>Pattern: Strategy (docs/lld/DESIGN_PATTERNS.md#1-strategy). DIP: the use case depends on this
 * interface, not on SQL. Contract for every implementation (checked by one shared test suite, LSP):
 * claim only seats that are AVAILABLE or whose hold expired before the database's {@code now()};
 * return exactly the seats claimed, each with its row version after the write; never claim a seat for two live holds; never deadlock.
 * All-or-nothing is the caller's job: it rolls back when fewer seats come back than were asked for.
 */
public interface SeatLockingStrategy {

    List<ClaimedSeat> claim(UUID showId, List<String> seatCodes, UUID holdId, UUID userId, Instant expiresAt);

    /** A seat that was claimed, with the price frozen on the show seat (BR-05). */
    record ClaimedSeat(String seatCode, long priceMinor, long version) { }
}
