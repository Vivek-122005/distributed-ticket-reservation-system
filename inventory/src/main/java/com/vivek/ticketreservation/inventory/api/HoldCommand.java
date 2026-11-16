package com.vivek.ticketreservation.inventory.api;

import java.util.List;
import java.util.UUID;

/** Input of {@link InventoryApi#hold}: seats are identified by code within one show. */
public record HoldCommand(UUID showId, UUID userId, List<String> seatCodes) { }
