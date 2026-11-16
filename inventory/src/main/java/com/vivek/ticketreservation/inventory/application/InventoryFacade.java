package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import org.springframework.stereotype.Component;

/**
 * Single entry point of the inventory module; delegates each operation to its own use case.
 *
 * <p>Pattern: Facade, see docs/lld/DESIGN_PATTERNS.md#9-facade. SRP: no logic here, only delegation.
 */
@Component
class InventoryFacade implements InventoryApi {
    private final CreateHoldUseCase createHold;

    InventoryFacade(CreateHoldUseCase createHold) { this.createHold = createHold; }

    @Override
    public HoldResult hold(HoldCommand command) { return createHold.execute(command); }
}
