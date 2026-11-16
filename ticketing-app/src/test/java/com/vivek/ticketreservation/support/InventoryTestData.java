package com.vivek.ticketreservation.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Resets the V1 demo inventory between tests so each test starts from 100 AVAILABLE seats. */
public final class InventoryTestData {
    public static final UUID DEMO_EVENT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private InventoryTestData() { }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.update("UPDATE seat_inventory SET status = 'AVAILABLE', hold_id = NULL, held_by_user_id = NULL, hold_expires_at = NULL");
        jdbc.update("DELETE FROM holds");
    }

    public static String seatStatus(JdbcTemplate jdbc, String seatCode) {
        return jdbc.queryForObject("SELECT status FROM seat_inventory WHERE event_id = ? AND seat_code = ?",
                String.class, DEMO_EVENT, seatCode);
    }
}
