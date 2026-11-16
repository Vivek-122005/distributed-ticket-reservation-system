package com.vivek.ticketreservation.inventory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's temporary reservation of 1–8 seats of one show. Aggregate root; framework-free.
 *
 * <p>Expiry rule, shared with the SQL claim (P1.2): a hold is expired when {@code expiresAt < now}.
 * At {@code now == expiresAt} it is still live, so "claimable by others" and "confirmable" never
 * overlap and never leave a gap. {@code now} always comes from the database clock (SRS A3).
 *
 * <p>Idempotency: confirming a CONFIRMED hold, or releasing a RELEASED/EXPIRED one, is a no-op that
 * returns {@code false}, so retries from the booking saga are safe (P1.4).
 */
public final class Hold {
    private final UUID id;
    private final UUID showId;
    private final UUID userId;
    private final Instant expiresAt;
    private HoldStatus status;

    public Hold(UUID id, UUID showId, UUID userId, HoldStatus status, Instant expiresAt) {
        this.id = Objects.requireNonNull(id);
        this.showId = Objects.requireNonNull(showId);
        this.userId = Objects.requireNonNull(userId);
        this.status = Objects.requireNonNull(status);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public boolean isExpired(Instant now) { return expiresAt.isBefore(now); }

    public boolean belongsTo(UUID user) { return userId.equals(user); }

    /** HELD → CONFIRMED while not expired. @return true if the state changed, false if already confirmed. */
    public boolean confirm(Instant now) {
        if (status == HoldStatus.CONFIRMED) return false;
        if (status == HoldStatus.HELD && isExpired(now)) throw new HoldExpiredException(id);
        moveTo(HoldStatus.CONFIRMED);
        return true;
    }

    /** HELD → RELEASED. @return false if already released or expired; a confirmed hold cannot be released. */
    public boolean release() {
        if (status == HoldStatus.RELEASED || status == HoldStatus.EXPIRED) return false;
        moveTo(HoldStatus.RELEASED);
        return true;
    }

    /** HELD → EXPIRED once past expiry (expiry worker, P2.1). @return false if not due or not HELD. */
    public boolean expire(Instant now) {
        if (status != HoldStatus.HELD || !isExpired(now)) return false;
        moveTo(HoldStatus.EXPIRED);
        return true;
    }

    private void moveTo(HoldStatus next) {
        if (!status.canTransitionTo(next)) throw new IllegalStateTransitionException("Hold " + id, status, next);
        status = next;
    }

    public UUID id() { return id; }
    public UUID showId() { return showId; }
    public UUID userId() { return userId; }
    public HoldStatus status() { return status; }
    public Instant expiresAt() { return expiresAt; }
}
