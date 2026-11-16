package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.HoldConflictException;
import com.vivek.ticketreservation.inventory.api.HoldConflictException.Reason;
import com.vivek.ticketreservation.inventory.api.HoldNotFoundException;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.application.port.DatabaseClock;
import com.vivek.ticketreservation.inventory.application.port.HoldStore;
import com.vivek.ticketreservation.inventory.application.port.InventoryEventPublisher;
import com.vivek.ticketreservation.inventory.application.port.ShowSeatStore;
import com.vivek.ticketreservation.inventory.application.port.ShowSeatStore.SeatVersion;
import com.vivek.ticketreservation.inventory.domain.Hold;
import com.vivek.ticketreservation.inventory.domain.IllegalStateTransitionException;
import com.vivek.ticketreservation.inventory.domain.event.HoldExpired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Releases a hold (customer cancel, saga compensation): hold → RELEASED, seats it still owns → AVAILABLE.
 *
 * <p>A HELD hold that is already past expiry is recorded as EXPIRED instead of RELEASED, which is what
 * really happened. Seats are freed only {@code WHERE hold_id = this hold}, so seats that were lazily
 * re-held by someone else are left alone; that is why the released count is not checked.
 * Recording an expiry publishes {@link HoldExpired}, exactly as the expiry worker does (P2.2), so
 * listeners see every expiry once, whoever recorded it. Idempotent: releasing a RELEASED or EXPIRED hold
 * returns it unchanged.
 */
@Service
public class ReleaseHoldUseCase {
    private final HoldStore holds;
    private final ShowSeatStore seats;
    private final DatabaseClock clock;
    private final HoldViews views;
    private final InventoryEventPublisher events;
    private final SeatEvents seatEvents;

    ReleaseHoldUseCase(HoldStore holds, ShowSeatStore seats, DatabaseClock clock, HoldViews views,
                       InventoryEventPublisher events, SeatEvents seatEvents) {
        this.holds = holds; this.seats = seats; this.clock = clock; this.views = views; this.events = events; this.seatEvents = seatEvents;
    }

    @Transactional
    public HoldResult execute(UUID holdId, UUID userId) {
        Hold hold = holds.lock(holdId).filter(h -> h.belongsTo(userId)).orElseThrow(() -> new HoldNotFoundException(holdId));
        Instant now = clock.now();
        try {
            boolean expired = hold.expire(now);
            if (expired || hold.release()) {
                List<SeatVersion> released = seats.releaseSeatsOfHolds(List.of(holdId)).getOrDefault(holdId, List.of());
                holds.saveStatus(hold);
                seatEvents.released(hold.showId(), released);
                // Same outcome as the expiry worker, so the same event: whoever records an expiry announces it.
                if (expired) events.publish(new HoldExpired(hold.id(), hold.showId(), hold.userId(), now,
                        released.stream().map(SeatVersion::seatCode).toList()));
            }
        } catch (IllegalStateTransitionException confirmed) {
            throw new HoldConflictException(Reason.HOLD_ALREADY_CONFIRMED, "Confirmed holds cannot be released");
        }
        return views.of(hold, now);
    }
}
