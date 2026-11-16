package com.vivek.ticketreservation.inventory.infrastructure.locking;

import com.vivek.ticketreservation.inventory.application.port.SeatLockingStrategy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link SeatLockingStrategy} (b): optimistic concurrency. Read without locks, let the domain decide,
 * then write each seat {@code WHERE id = ? AND version = ?}. A changed version means someone else wrote
 * the row since we read it: re-read and retry, up to {@link #MAX_ATTEMPTS}.
 *
 * <p>No locks are held while deciding, which is ideal when conflicts are rare. Under flash-sale
 * contention most attempts lose the version race and redo the work. Seats are written in seat-code
 * order, so the short row locks taken by the writes can't deadlock. Seats already written by this hold
 * in an earlier attempt are recognised by hold id and kept.
 */
class OptimisticLockStrategy implements SeatLockingStrategy {
    static final int MAX_ATTEMPTS = 3;
    private final SeatRows rows;

    OptimisticLockStrategy(SeatRows rows) { this.rows = rows; }

    @Override
    public List<ClaimedSeat> claim(UUID showId, List<String> seatCodes, UUID holdId, UUID userId, Instant expiresAt) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            SeatRows.Snapshot snapshot = rows.load(showId, seatCodes, false);
            if (snapshot.rows().size() != seatCodes.size()) return List.of();
            List<SeatRows.Row> todo = snapshot.rows().stream().filter(r -> !holdId.equals(r.seat().holdId())).toList();
            if (!todo.stream().allMatch(r -> r.seat().isClaimable(snapshot.now()))) return List.of();   // really taken

            boolean conflict = false;
            for (SeatRows.Row row : todo) {
                int written = rows.markHeld(List.of(row.seat().id()), holdId, userId, expiresAt, "AND version = :version",
                        new MapSqlParameterSource("version", row.version())).size();
                if (written == 0) { conflict = true; break; }
            }
            if (!conflict) return rows.ownedBy(holdId);
        }
        return rows.ownedBy(holdId);   // gave up: fewer seats than requested, the caller rolls back
    }
}
