package com.vivek.ticketreservation.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outcome of a successful hold, independent of any transport (HTTP DTOs live in {@code web}). */
public record HoldResult(UUID holdId, Instant expiresAt, List<String> seatCodes) { }
