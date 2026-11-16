package com.vivek.ticketreservation.inventory.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.UUID;

/** Persistence of sellable seats ({@code inventory.show_seats}) and the hot claim query. */
public interface ShowSeatRepository extends JpaRepository<ShowSeatRow, UUID> {

    /**
     * Compare-and-set claim of one seat: succeeds (returns 1) only if the seat is still AVAILABLE.
     * Pattern: docs/lld/DESIGN_PATTERNS.md#3-compare-and-set-conditional-update.
     */
    @Modifying
    @Query(value = """
            UPDATE inventory.show_seats
               SET status = 'HELD', hold_id = :holdId, held_by_user_id = :userId,
                   hold_expires_at = :expiresAt, version = version + 1
             WHERE show_id = :showId AND seat_code = :seatCode AND status = 'AVAILABLE'
            """, nativeQuery = true)
    int claimAvailableSeat(@Param("showId") UUID showId, @Param("seatCode") String seatCode, @Param("holdId") UUID holdId,
                           @Param("userId") UUID userId, @Param("expiresAt") Instant expiresAt);
}
