package com.vivek.ticketreservation.inventory.web;

import com.vivek.ticketreservation.inventory.api.HeldSeat;
import com.vivek.ticketreservation.inventory.api.HoldResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** HTTP shape of a hold, schema {@code Hold} in docs/api/inventory-service.yaml. */
public record HoldResponse(UUID holdId, UUID showId, String status, Instant expiresAt,
                           List<HeldSeat> seats, long totalMinor, String currency) {

    static HoldResponse of(HoldResult result) {
        return new HoldResponse(result.holdId(), result.showId(), result.status().name(), result.expiresAt(),
                result.seats(), result.totalMinor(), result.currency());
    }
}
