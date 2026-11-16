package com.vivek.ticketreservation.inventory.web;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Inbound HTTP adapter: binds the request, calls the module API, maps the result to the DTO. */
@RestController
@RequestMapping("/api/v1/shows/{showId}/holds")
public class InventoryController {
    private final InventoryApi inventory;
    public InventoryController(InventoryApi inventory) { this.inventory = inventory; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse createHold(@PathVariable UUID showId, @RequestHeader("X-User-Id") UUID userId,
                                   @Valid @RequestBody CreateHoldRequest request) {
        HoldResult result = inventory.hold(new HoldCommand(showId, userId, request.seatCodes()));
        return new HoldResponse(result.holdId(), result.showId(), "HELD", result.expiresAt(), result.seatCodes());
    }
}
