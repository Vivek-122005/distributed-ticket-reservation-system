package com.vivek.ticketreservation.inventory;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P1.2: an expired hold never blocks a seat, even if no expiry worker has run (FR-04, invariant 4). */
@IntegrationTest
class LazyExpiryIT {

    @Autowired InventoryApi inventory;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() { InventoryTestData.reset(jdbc); }

    @Test
    void seatOfAnExpiredHoldCanBeHeldAgainWithoutTheExpiryWorker() {
        HoldResult first = inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A1", "A2")));
        expire(first.holdId());

        HoldResult second = inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A2", "A3")));

        assertThat(holdOf("A2")).isEqualTo(second.holdId());
        assertThat(holdOf("A1")).as("A1 not requested, keeps the stale hold until the worker runs").isEqualTo(first.holdId());
    }

    @Test
    void seatOfALiveHoldStaysUnavailable() {
        inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A4")));

        assertThatThrownBy(() -> inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A4"))))
                .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    void bookedSeatIsNeverReclaimedEvenWithAnOldExpiry() {
        HoldResult first = inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A5")));
        expire(first.holdId());
        jdbc.update("UPDATE inventory.show_seats SET status = 'BOOKED' WHERE hold_id = ?", first.holdId());

        assertThatThrownBy(() -> inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A5"))))
                .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    void expiryComesFromTheDatabaseClock() {
        Instant dbNow = jdbc.queryForObject("SELECT now()", Instant.class);

        HoldResult hold = inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A6")));

        assertThat(Duration.between(dbNow, hold.expiresAt())).isBetween(Duration.ofMinutes(5), Duration.ofMinutes(5).plusSeconds(5));
        Instant seatExpiry = jdbc.queryForObject(
                "SELECT hold_expires_at FROM inventory.show_seats WHERE hold_id = ?", Instant.class, hold.holdId());
        assertThat(seatExpiry).as("seat and hold expire at the same instant").isEqualTo(hold.expiresAt());
    }

    /** Simulates the 5 minutes passing by moving the hold and its seats into the past. */
    private void expire(UUID holdId) {
        jdbc.update("UPDATE inventory.holds SET expires_at = now() - interval '1 second' WHERE id = ?", holdId);
        jdbc.update("UPDATE inventory.show_seats SET hold_expires_at = now() - interval '1 second' WHERE hold_id = ?", holdId);
    }

    private UUID holdOf(String seatCode) {
        return jdbc.queryForObject("SELECT hold_id FROM inventory.show_seats WHERE show_id = ? AND seat_code = ?",
                UUID.class, DEMO_SHOW, seatCode);
    }
}
