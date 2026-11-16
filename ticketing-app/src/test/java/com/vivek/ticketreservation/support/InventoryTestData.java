package com.vivek.ticketreservation.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Resets the V2 demo show between tests so each test starts from 100 AVAILABLE seats, on sale. */
public final class InventoryTestData {
    public static final UUID DEMO_EVENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID DEMO_SHOW = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private InventoryTestData() { }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.update("UPDATE inventory.show_seats SET status = 'AVAILABLE', hold_id = NULL, held_by_user_id = NULL, hold_expires_at = NULL, price_minor = 150000");
        jdbc.update("DELETE FROM inventory.holds");
        jdbc.update("UPDATE inventory.shows SET status = 'ON_SALE' WHERE id = ?", DEMO_SHOW);
    }

    public static String seatStatus(JdbcTemplate jdbc, String seatCode) {
        return jdbc.queryForObject("SELECT status FROM inventory.show_seats WHERE show_id = ? AND seat_code = ?",
                String.class, DEMO_SHOW, seatCode);
    }

    public static void setShowStatus(JdbcTemplate jdbc, String status) {
        jdbc.update("UPDATE inventory.shows SET status = ? WHERE id = ?", status, DEMO_SHOW);
    }
}
