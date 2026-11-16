package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldConflictException;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.inventory.api.ShowNotFoundException;
import com.vivek.ticketreservation.inventory.api.ShowNotOnSaleException;
import com.vivek.ticketreservation.inventory.application.port.HoldMetrics;
import com.vivek.ticketreservation.inventory.application.port.HoldMetrics.Outcome;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Single entry point of the inventory module; delegates each operation to its own use case.
 *
 * <p>Pattern: Facade, see docs/lld/DESIGN_PATTERNS.md#9-facade. Besides delegation it does one
 * cross-cutting job: metering (P1.9). It sits outside the use cases' transactions, so the recorded
 * latency includes the commit, which is what a caller waits for.
 */
@Component
class InventoryFacade implements InventoryApi {
    private final CreateHoldUseCase createHold;
    private final ConfirmHoldUseCase confirmHold;
    private final ReleaseHoldUseCase releaseHold;
    private final GetHoldUseCase getHold;
    private final HoldMetrics metrics;

    InventoryFacade(CreateHoldUseCase createHold, ConfirmHoldUseCase confirmHold, ReleaseHoldUseCase releaseHold,
                    GetHoldUseCase getHold, HoldMetrics metrics) {
        this.createHold = createHold; this.confirmHold = confirmHold; this.releaseHold = releaseHold;
        this.getHold = getHold; this.metrics = metrics;
    }

    @Override
    public HoldResult hold(HoldCommand command) {
        long start = System.nanoTime();
        Outcome outcome = Outcome.ERROR;
        try {
            HoldResult result = createHold.execute(command);
            outcome = Outcome.HELD;
            return result;
        } catch (SeatUnavailableException e) { outcome = Outcome.SEAT_UNAVAILABLE; throw e; }
        catch (ShowNotOnSaleException e) { outcome = Outcome.SHOW_NOT_ON_SALE; throw e; }
        catch (ShowNotFoundException e) { outcome = Outcome.SHOW_NOT_FOUND; throw e; }
        catch (IllegalArgumentException e) { outcome = Outcome.INVALID_REQUEST; throw e; }
        finally { metrics.holdAttempt(outcome, Duration.ofNanos(System.nanoTime() - start)); }
    }

    @Override public HoldResult confirm(UUID holdId, UUID userId) { return transition("confirm", () -> confirmHold.execute(holdId, userId)); }
    @Override public HoldResult release(UUID holdId, UUID userId) { return transition("release", () -> releaseHold.execute(holdId, userId)); }
    @Override public HoldResult get(UUID holdId, UUID userId) { return getHold.execute(holdId, userId); }

    private HoldResult transition(String operation, Supplier<HoldResult> call) {
        try {
            HoldResult result = call.get();
            metrics.holdTransition(operation, result.status().name());
            return result;
        } catch (HoldConflictException e) {
            metrics.holdTransition(operation, e.reason().name());
            throw e;
        }
    }
}
