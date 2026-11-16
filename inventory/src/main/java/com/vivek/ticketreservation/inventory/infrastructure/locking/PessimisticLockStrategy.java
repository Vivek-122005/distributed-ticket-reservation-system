package com.vivek.ticketreservation.inventory.infrastructure.locking;

import com.vivek.ticketreservation.inventory.application.port.SeatLockingStrategy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link SeatLockingStrategy} (a): pessimistic locking. {@code SELECT … ORDER BY seat_code FOR UPDATE}
 * locks every requested row (taken or not) until commit; the {@code ShowSeat} domain object decides;
 * then one {@code UPDATE}.
 *
 * <p>Correct and easy to reason about. Costs: row locks are held for the whole transaction, including
 * the Java round trip between SELECT and UPDATE, and also on seats that turn out to be taken, so
 * requests queue behind each other on hot rows. Ordered locking prevents deadlocks.
 */
class PessimisticLockStrategy implements SeatLockingStrategy {
    private final SeatRows rows;

    PessimisticLockStrategy(SeatRows rows) { this.rows = rows; }

    @Override
    public List<ClaimedSeat> claim(UUID showId, List<String> seatCodes, UUID holdId, UUID userId, Instant expiresAt) {
        SeatRows.Snapshot snapshot = rows.load(showId, seatCodes, true);
        if (snapshot.rows().size() != seatCodes.size() || !snapshot.allClaimable()) return List.of();
        snapshot.rows().forEach(r -> r.seat().hold(holdId, expiresAt, snapshot.now()));   // domain re-validates
        List<UUID> ids = snapshot.rows().stream().map(r -> r.seat().id()).toList();
        return rows.markHeld(ids, holdId, userId, expiresAt, "", new MapSqlParameterSource());
    }
}
