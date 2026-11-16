package com.vivek.ticketreservation.inventory.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain event: a hold ran out before it was confirmed; {@code releasedSeatCodes} went back to stock
 * (seats lazily re-held by someone else are not listed: they were never released by this hold).
 * Consumed in-process today (P2.1); carried by the outbox to Kafka {@code inventory.events} in P5.2.
 */
public record HoldExpired(UUID holdId, UUID showId, UUID userId, Instant expiredAt, List<String> releasedSeatCodes) { }
