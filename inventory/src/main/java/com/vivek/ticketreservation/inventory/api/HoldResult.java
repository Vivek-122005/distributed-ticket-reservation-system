package com.vivek.ticketreservation.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A hold as seen by other modules, independent of any transport (HTTP DTOs live in {@code web}). */
public record HoldResult(UUID holdId, UUID showId, HoldState status, Instant expiresAt,
                         List<HeldSeat> seats, long totalMinor, String currency) { }
