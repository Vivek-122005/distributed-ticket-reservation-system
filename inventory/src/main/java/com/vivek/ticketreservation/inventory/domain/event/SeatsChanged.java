package com.vivek.ticketreservation.inventory.domain.event;

import com.vivek.ticketreservation.inventory.domain.SeatStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain event: seats of one show changed state (held, booked, released, expired), with each seat's row
 * {@code version} after the change (P2.3).
 *
 * <p>The version makes the event safe to apply in any order: a consumer keeps, per seat, only the
 * highest version it has seen. Two events about the same seat that arrive swapped (a release processed
 * after the re-hold that followed it) therefore can't leave a stale state behind. Feeds the Redis seat
 * cache now and Kafka {@code seat.updates} (live seat map, P5.11) later.
 */
public record SeatsChanged(UUID showId, List<SeatState> seats) {

    /** {@code holdExpiresAt} is set only for HELD. */
    public record SeatState(String seatCode, SeatStatus status, Instant holdExpiresAt, long version) { }
}
