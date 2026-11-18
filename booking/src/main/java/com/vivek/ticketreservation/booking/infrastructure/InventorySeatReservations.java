package com.vivek.ticketreservation.booking.infrastructure;

import com.vivek.ticketreservation.booking.application.port.SeatReservationException;
import com.vivek.ticketreservation.booking.application.port.SeatReservationException.Reason;
import com.vivek.ticketreservation.booking.application.port.SeatReservations;
import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldConflictException;
import com.vivek.ticketreservation.inventory.api.HoldNotFoundException;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.inventory.api.ShowNotFoundException;
import com.vivek.ticketreservation.inventory.api.ShowNotOnSaleException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * In-process adapter from booking's {@link SeatReservations} port to inventory's published
 * {@link InventoryApi} (P3.3): translates types and exceptions, nothing else.
 *
 * <p>Pattern: Adapter, see docs/lld/DESIGN_PATTERNS.md#8-adapter. Inventory's exceptions stop here,
 * so booking's use cases and error mapping know only booking's own vocabulary.
 */
@Component
class InventorySeatReservations implements SeatReservations {
    private final InventoryApi inventory;

    InventorySeatReservations(InventoryApi inventory) { this.inventory = inventory; }

    @Override
    public Reservation hold(UUID showId, UUID userId, List<String> seatCodes) {
        try {
            HoldResult hold = inventory.hold(new HoldCommand(showId, userId, seatCodes));
            return new Reservation(hold.holdId(),
                    hold.seats().stream().map(s -> new ReservedSeat(s.seatCode(), s.priceMinor())).toList(),
                    hold.currency(), hold.expiresAt());
        } catch (SeatUnavailableException e) {
            throw new SeatReservationException(Reason.SEAT_UNAVAILABLE, "One or more requested seats are no longer available.", e);
        } catch (ShowNotFoundException e) {
            throw new SeatReservationException(Reason.SHOW_NOT_FOUND, e.getMessage(), e);
        } catch (ShowNotOnSaleException e) {
            throw new SeatReservationException(Reason.SHOW_NOT_ON_SALE, e.getMessage(), e);
        }
    }

    @Override
    public void confirm(UUID holdId, UUID userId) {
        transition(() -> inventory.confirm(holdId, userId));
    }

    @Override
    public void release(UUID holdId, UUID userId) {
        transition(() -> inventory.release(holdId, userId));
    }

    private static void transition(Runnable call) {
        try {
            call.run();
        } catch (HoldNotFoundException e) {
            throw new SeatReservationException(Reason.HOLD_NOT_FOUND, e.getMessage(), e);
        } catch (HoldConflictException e) {
            Reason reason = e.reason() == HoldConflictException.Reason.HOLD_EXPIRED ? Reason.HOLD_EXPIRED : Reason.ILLEGAL_STATE_TRANSITION;
            throw new SeatReservationException(reason, e.getMessage(), e);
        }
    }
}
