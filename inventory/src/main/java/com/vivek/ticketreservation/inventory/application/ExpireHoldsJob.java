package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.application.port.DatabaseClock;
import com.vivek.ticketreservation.inventory.application.port.HoldStore;
import com.vivek.ticketreservation.inventory.application.port.InventoryEventPublisher;
import com.vivek.ticketreservation.inventory.application.port.ShowSeatStore;
import com.vivek.ticketreservation.inventory.domain.Hold;
import com.vivek.ticketreservation.inventory.domain.event.HoldExpired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Expires holds that ran out (FR-04, UC-07): hold HELD → EXPIRED, the seats it still owns → AVAILABLE,
 * one {@link HoldExpired} event per hold (P2.1).
 *
 * <p>Since lazy expiry (P1.2) this job is <b>cleanup, not availability</b>: an expired seat is already
 * claimable before the job runs. The job makes the stored state truthful, frees seats for the seat map
 * and tells other parts of the system (booking → EXPIRED, cache) that the hold is over.
 *
 * <p>Concurrency: holds are locked with {@code FOR UPDATE SKIP LOCKED}, so any number of replicas can run
 * this job at once without picking the same hold (no leader needed, P2.2). Lock order is hold row, then
 * seat rows, the same as confirm and release; a hold being confirmed right now is simply skipped and,
 * once confirmed, is no longer HELD. Seats are released only {@code WHERE hold_id = this hold}, so a seat
 * that was lazily re-held by someone else is never touched.
 */
@Service
public class ExpireHoldsJob {
    private final HoldStore holds;
    private final ShowSeatStore seats;
    private final DatabaseClock clock;
    private final InventoryEventPublisher events;

    ExpireHoldsJob(HoldStore holds, ShowSeatStore seats, DatabaseClock clock, InventoryEventPublisher events) {
        this.holds = holds; this.seats = seats; this.clock = clock; this.events = events;
    }

    /** Expires at most {@code limit} holds in one transaction. @return how many holds were expired */
    @Transactional
    public int runBatch(int limit) {
        return expire(holds.lockExpired(limit));
    }

    /** Ids of due holds, for {@link #runOne} when a batch failed. Read-only, takes no locks. */
    @Transactional(readOnly = true)
    public List<UUID> dueHoldIds(int limit) { return holds.findExpiredIds(limit); }

    /**
     * Expires one hold in its own transaction. Used to isolate a hold that makes a whole batch fail
     * (a "poison" row), so the others still expire. @return true if this call expired it
     */
    @Transactional
    public boolean runOne(UUID holdId) {
        return expire(holds.lockIfExpired(holdId).map(List::of).orElse(List.of())) == 1;
    }

    private int expire(List<Hold> due) {
        if (due.isEmpty()) return 0;
        Instant now = clock.now();
        List<Hold> expired = due.stream().filter(h -> h.expire(now)).toList();   // the domain re-checks each one
        Map<UUID, List<String>> released = seats.releaseSeatsOfHolds(expired.stream().map(Hold::id).toList());
        holds.saveStatuses(expired);
        expired.forEach(h -> events.publish(new HoldExpired(h.id(), h.showId(), h.userId(), now,
                released.getOrDefault(h.id(), List.of()))));
        return expired.size();
    }
}
