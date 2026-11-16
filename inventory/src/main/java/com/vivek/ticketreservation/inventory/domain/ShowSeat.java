package com.vivek.ticketreservation.inventory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A physical seat as sold for one show: the unit that is locked.
 *
 * <p>The hot claim path enforces these same rules in one SQL statement (ConditionalUpdateStrategy);
 * this class is the readable, unit-tested statement of them and is used by the load-then-update
 * locking strategies (P1.7). Ownership checks use the hold id, so a stale actor (an old hold, a late
 * expiry worker) can never move a seat that has been re-held by someone else.
 */
public final class ShowSeat {
    private final UUID id;
    private final String seatCode;
    private final long priceMinor;
    private SeatStatus status;
    private UUID holdId;
    private Instant holdExpiresAt;

    public ShowSeat(UUID id, String seatCode, long priceMinor, SeatStatus status, UUID holdId, Instant holdExpiresAt) {
        this.id = Objects.requireNonNull(id);
        this.seatCode = Objects.requireNonNull(seatCode);
        this.priceMinor = priceMinor;
        this.status = Objects.requireNonNull(status);
        this.holdId = holdId;
        this.holdExpiresAt = holdExpiresAt;
    }

    /** AVAILABLE, or HELD by an expired hold (lazy expiry), at {@code now}. */
    public boolean isClaimable(Instant now) {
        return status == SeatStatus.AVAILABLE || (status == SeatStatus.HELD && holdExpiresAt.isBefore(now));
    }

    public void hold(UUID newHoldId, Instant expiresAt, Instant now) {
        if (!isClaimable(now)) throw new IllegalStateTransitionException("Seat " + seatCode, status, SeatStatus.HELD);
        status = SeatStatus.HELD;
        holdId = Objects.requireNonNull(newHoldId);
        holdExpiresAt = Objects.requireNonNull(expiresAt);
    }

    public void book(UUID byHold) {
        requireHeldBy(byHold, SeatStatus.BOOKED);
        status = SeatStatus.BOOKED;
    }

    public void release(UUID byHold) {
        requireHeldBy(byHold, SeatStatus.AVAILABLE);
        status = SeatStatus.AVAILABLE;
        holdId = null;
        holdExpiresAt = null;
    }

    private void requireHeldBy(UUID byHold, SeatStatus next) {
        if (!status.canTransitionTo(next) || !Objects.equals(holdId, byHold)) {
            throw new IllegalStateTransitionException("Seat " + seatCode + " (hold " + holdId + ")", status, next);
        }
    }

    public UUID id() { return id; }
    public String seatCode() { return seatCode; }
    public long priceMinor() { return priceMinor; }
    public SeatStatus status() { return status; }
    public UUID holdId() { return holdId; }
    public Instant holdExpiresAt() { return holdExpiresAt; }
}
