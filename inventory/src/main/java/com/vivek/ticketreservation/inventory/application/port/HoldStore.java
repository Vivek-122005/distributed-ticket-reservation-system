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
}
