package com.vivek.ticketreservation.inventory.application.port;

import com.vivek.ticketreservation.inventory.domain.Hold;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: persistence of {@link Hold} aggregates. Pattern: Repository behind a port (DIP).
 *
 * <p>{@link #createHeld} lets the database compute {@code expires_at} from its own clock (SRS A3),
 * which is why creation is not a plain {@code save(new Hold(...))}.
 */
public interface HoldStore {

    /** Inserts a HELD hold for these seats, expiring {@code ttl} after the database's {@code now()}. */
    Hold createHeld(UUID id, UUID showId, UUID userId, List<String> seatCodes, Duration ttl);

    Optional<Hold> find(UUID holdId);

    /**
     * Loads the hold and locks its row until the transaction ends ({@code SELECT … FOR UPDATE}), so
     * confirm, release and expiry of the same hold run one after another, never interleaved.
     */
    Optional<Hold> lock(UUID holdId);

    /** Writes the hold's current status. */
    void saveStatus(Hold hold);

    /**
     * Locks up to {@code limit} HELD holds whose expiry has passed (database clock), oldest first,
     * skipping rows another transaction already holds ({@code FOR UPDATE SKIP LOCKED}). Several expiry
     * workers can therefore run at once and never pick the same hold or wait for each other.
     */
    List<Hold> lockExpired(int limit);

    /** Ids of HELD holds past expiry, oldest first, without locking (for the one-by-one fallback). */
    List<UUID> findExpiredIds(int limit);

    /** Locks one hold if it is still HELD and past expiry; empty if it changed or another worker has it. */
    Optional<Hold> lockIfExpired(UUID holdId);

    /** Writes the status of every hold in one statement. */
    void saveStatuses(List<Hold> holds);
}
