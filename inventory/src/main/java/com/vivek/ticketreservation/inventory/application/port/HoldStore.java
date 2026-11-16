package com.vivek.ticketreservation.inventory.application.port;

import com.vivek.ticketreservation.inventory.domain.Hold;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: persistence of {@link Hold} aggregates. Pattern: Repository behind a port (DIP).
 *
 * <p>{@link #createHeld} lets the database compute {@code expires_at} from its own clock (SRS A3),
 * which is why creation is not a plain {@code save(new Hold(...))}.
 */
public interface HoldStore {

    /** Inserts a HELD hold expiring {@code ttl} after the database's {@code now()}. */
    Hold createHeld(UUID id, UUID showId, UUID userId, Duration ttl);

    Optional<Hold> find(UUID holdId);
}
