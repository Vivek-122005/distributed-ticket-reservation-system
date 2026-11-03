package com.vivek.ticketreservation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.UUID;

public interface SeatInventoryRepository extends JpaRepository<SeatInventoryRow, UUID> {
    @Modifying
    @Query(value = "UPDATE seat_inventory SET status = 'HELD', hold_id = :holdId, held_by_user_id = :userId, hold_expires_at = :expiresAt, version = version + 1 WHERE event_id = :eventId AND seat_code = :seatCode AND status = 'AVAILABLE'", nativeQuery = true)
    int claimAvailableSeat(@Param("eventId") UUID eventId, @Param("seatCode") String seatCode, @Param("holdId") UUID holdId, @Param("userId") UUID userId, @Param("expiresAt") Instant expiresAt);
}
