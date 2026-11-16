package com.vivek.ticketreservation.inventory.application.port;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port: a fast, possibly stale pre-check of seat availability, run <b>before</b> the database
 * transaction (P2.4, ADR 003). It may only reject more, never accept more: Postgres still decides every
 * hold that gets through. An implementation that cannot answer (cache cold, Redis down, disabled) must
 * return {@link Verdict#SKIPPED} quickly, never throw.
 */
public interface SeatFilter {

    enum Verdict {
        /** At least one seat is known to be taken: reject without touching Postgres. */
        REJECTED,
        /** Seats looked free and are now marked pending for a short time; go ahead to Postgres. */
        RESERVED,
        /** No opinion (not cached, Redis unavailable, disabled): go ahead to Postgres. */
        SKIPPED
    }

    /** The outcome plus what {@link #cancel} needs to undo a reservation. */
    record Reservation(Verdict verdict, UUID showId, List<String> seatCodes, String token) {
        public static Reservation skipped() { return new Reservation(Verdict.SKIPPED, null, List.of(), null); }
    }

    Reservation tryReserve(UUID showId, List<String> seatCodes);

    /** Removes this reservation's pending marks after the database refused the hold. Never throws. */
    void cancel(Reservation reservation);
}
