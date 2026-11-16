package com.vivek.ticketreservation.inventory.web;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/**
 * Inbound HTTP adapter for the hold lifecycle: binds the request, calls the module API, maps the
 * result to the DTO. {@code X-User-Id} comes from a trusted internal caller until JWT auth (P6.2).
 */
@RestController
@RequestMapping("/api/v1")
public class InventoryController {
    private static final String USER = "X-User-Id";
    private final InventoryApi inventory;

    public InventoryController(InventoryApi inventory) { this.inventory = inventory; }

    @PostMapping("/shows/{showId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse createHold(@PathVariable UUID showId, @RequestHeader(USER) UUID userId,
                                   @Valid @RequestBody CreateHoldRequest request) {
        return HoldResponse.of(inventory.hold(new HoldCommand(showId, userId, request.seatCodes())));
    }

    @GetMapping("/holds/{holdId}")
    public HoldResponse getHold(@PathVariable UUID holdId, @RequestHeader(USER) UUID userId) {
        return HoldResponse.of(inventory.get(holdId, userId));
    }

    @PostMapping("/holds/{holdId}/confirm")
    public HoldResponse confirmHold(@PathVariable UUID holdId, @RequestHeader(USER) UUID userId) {
        return HoldResponse.of(inventory.confirm(holdId, userId));
    }

    @DeleteMapping("/holds/{holdId}")
    public HoldResponse releaseHold(@PathVariable UUID holdId, @RequestHeader(USER) UUID userId) {
        return HoldResponse.of(inventory.release(holdId, userId));
    }
}
