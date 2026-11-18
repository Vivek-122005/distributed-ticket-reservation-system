package com.vivek.ticketreservation.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** Clears the booking schema and resets the demo show, so booking tests start from no bookings and 100 free seats. */
public final class BookingTestData {
    private BookingTestData() { }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM booking.booking_seats");
        jdbc.update("DELETE FROM booking.bookings");
        InventoryTestData.reset(jdbc);
    }

    public static long bookings(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM booking.bookings", Long.class);
        return count == null ? 0 : count;
    }
}
