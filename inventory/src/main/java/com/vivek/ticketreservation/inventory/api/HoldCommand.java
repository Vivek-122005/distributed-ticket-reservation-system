package com.vivek.ticketreservation.inventory.api;

import java.util.List;
import java.util.UUID;

/** Input of {@link InventoryApi#hold}. Becomes show-based in P1.0. */
public record HoldCommand(UUID eventId, UUID userId, List<String> seatCodes) { }
