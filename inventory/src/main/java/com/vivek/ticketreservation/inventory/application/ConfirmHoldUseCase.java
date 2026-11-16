package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.HoldConflictException;
import com.vivek.ticketreservation.inventory.api.HoldConflictException.Reason;
import com.vivek.ticketreservation.inventory.api.HoldNotFoundException;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.application.port.DatabaseClock;
import com.vivek.ticketreservation.inventory.application.port.HoldStore;
import com.vivek.ticketreservation.inventory.application.port.ShowSeatStore;
import com.vivek.ticketreservation.inventory.domain.Hold;
import com.vivek.ticketreservation.inventory.domain.HoldExpiredException;
import com.vivek.ticketreservation.inventory.domain.IllegalStateTransitionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Confirms a hold after payment: hold HELD → CONFIRMED, its seats HELD → BOOKED (FR-06).
 *
 * <p>Concurrency:
 * <ul>
 *   <li>The hold row is locked first ({@link HoldStore#lock}), so confirm, release and expiry of one
 *       hold are serialised.</li>
 *   <li>Seats are booked only {@code WHERE hold_id = this hold AND status = 'HELD'}. If the hold expired
 *       a moment after our check and someone lazily re-held a seat (P1.2), fewer seats are booked than
 *       the hold has; we then treat it as expired and roll everything back. A confirmed hold therefore
 *       always owns every one of its seats.</li>
 * </ul>
 * Idempotent: a repeat confirm of a CONFIRMED hold returns it unchanged (saga retries are safe).
 */
@Service
public class ConfirmHoldUseCase {
    private final HoldStore holds;
    private final ShowSeatStore seats;
    private final DatabaseClock clock;
    private final HoldViews views;

    ConfirmHoldUseCase(HoldStore holds, ShowSeatStore seats, DatabaseClock clock, HoldViews views) {
        this.holds = holds; this.seats = seats; this.clock = clock; this.views = views;
    }

    @Transactional
    public HoldResult execute(UUID holdId, UUID userId) {
        Hold hold = holds.lock(holdId).filter(h -> h.belongsTo(userId)).orElseThrow(() -> new HoldNotFoundException(holdId));
        Instant now = clock.now();
        try {
            if (hold.confirm(now)) {
                if (seats.bookSeatsOf(holdId) != hold.seatCodes().size()) throw new HoldExpiredException(holdId);
                holds.saveStatus(hold);
            }
        } catch (HoldExpiredException expired) {
            throw new HoldConflictException(Reason.HOLD_EXPIRED, expired.getMessage());
        } catch (IllegalStateTransitionException released) {
            throw new HoldConflictException(Reason.ILLEGAL_STATE_TRANSITION, released.getMessage());
        }
        return views.of(hold, now);
    }
}
