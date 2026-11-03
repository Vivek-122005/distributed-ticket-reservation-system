package com.vivek.ticketreservation.api;

import com.vivek.ticketreservation.application.InventoryApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{eventId}/holds")
public class InventoryController {
    private final InventoryApplicationService inventory;
    public InventoryController(InventoryApplicationService inventory) { this.inventory = inventory; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse createHold(@PathVariable UUID eventId, @RequestHeader("X-User-Id") UUID userId,
                                   @Valid @RequestBody CreateHoldRequest request) {
        return inventory.createHold(eventId, userId, request.seatCodes());
    }
}
