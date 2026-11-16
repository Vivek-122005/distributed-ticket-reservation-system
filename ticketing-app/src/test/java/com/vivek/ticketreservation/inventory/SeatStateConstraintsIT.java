package com.vivek.ticketreservation.inventory;

import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V3: the database itself refuses impossible seat rows, whatever code path writes them (P1.3). */
@IntegrationTest
class SeatStateConstraintsIT {

    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() { InventoryTestData.reset(jdbc); }

    @Test
    void heldSeatWithoutHoldOrExpiryIsRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE inventory.show_seats SET status = 'HELD' WHERE show_id = ? AND seat_code = 'A1'", DEMO_SHOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_held_has_hold_and_expiry");
    }

    @Test
    void availableSeatThatStillPointsAtAHoldIsRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE inventory.show_seats SET hold_expires_at = now() WHERE show_id = ? AND seat_code = 'A1'", DEMO_SHOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_available_has_no_hold");
    }

    @Test
    void bookedSeatWithoutHoldIsRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE inventory.show_seats SET status = 'BOOKED' WHERE show_id = ? AND seat_code = 'A1'", DEMO_SHOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_booked_has_hold");
    }
}
