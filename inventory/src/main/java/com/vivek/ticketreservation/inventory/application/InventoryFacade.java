package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single entry point of the inventory module; delegates each operation to its own use case.
 *
 * <p>Pattern: Facade, see docs/lld/DESIGN_PATTERNS.md#9-facade. SRP: no logic here, only delegation.
 */
@Component
class InventoryFacade implements InventoryApi {
    private final CreateHoldUseCase createHold;
    private final ConfirmHoldUseCase confirmHold;
    private final ReleaseHoldUseCase releaseHold;
    private final GetHoldUseCase getHold;

    InventoryFacade(CreateHoldUseCase createHold, ConfirmHoldUseCase confirmHold,
                    ReleaseHoldUseCase releaseHold, GetHoldUseCase getHold) {
        this.createHold = createHold; this.confirmHold = confirmHold; this.releaseHold = releaseHold; this.getHold = getHold;
    }

    @Override public HoldResult hold(HoldCommand command) { return createHold.execute(command); }
    @Override public HoldResult confirm(UUID holdId, UUID userId) { return confirmHold.execute(holdId, userId); }
    @Override public HoldResult release(UUID holdId, UUID userId) { return releaseHold.execute(holdId, userId); }
    @Override public HoldResult get(UUID holdId, UUID userId) { return getHold.execute(holdId, userId); }
}
