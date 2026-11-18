package com.vivek.ticketreservation.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** Clears the booking schema and resets the demo show, so booking tests start from no bookings and 100 free seats. */
public final class BookingTestData {
    private BookingTestData() { }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM booking.idempotency_keys");
        jdbc.update("DELETE FROM payment.payments");
        jdbc.update("DELETE FROM booking.booking_sagas");
        jdbc.update("DELETE FROM booking.booking_seats");
        jdbc.update("DELETE FROM booking.bookings");
        InventoryTestData.reset(jdbc);
    }

    public static long bookings(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM booking.bookings", Long.class);
        return count == null ? 0 : count;
    }

    public static String bookingStatus(JdbcTemplate jdbc, Object bookingId) {
        return jdbc.queryForObject("SELECT status FROM booking.bookings WHERE id = ?::uuid", String.class, bookingId.toString());
    }

    public static String sagaStep(JdbcTemplate jdbc, Object bookingId) {
        return jdbc.queryForObject("SELECT step FROM booking.booking_sagas WHERE booking_id = ?::uuid", String.class, bookingId.toString());
    }

    public static String paymentStatus(JdbcTemplate jdbc, Object bookingId) {
        return jdbc.queryForObject("SELECT status FROM payment.payments WHERE booking_id = ?::uuid", String.class, bookingId.toString());
    }

    /** Moves a booking's hold (and the copy on the booking) into the past, as if the user waited too long. */
    public static void expireHold(JdbcTemplate jdbc, Object bookingId) {
        String id = bookingId.toString();
        jdbc.update("UPDATE inventory.holds SET expires_at = now() - interval '1 minute' WHERE id = (SELECT hold_id FROM booking.bookings WHERE id = ?::uuid)", id);
        jdbc.update("UPDATE inventory.show_seats SET hold_expires_at = now() - interval '1 minute' WHERE hold_id = (SELECT hold_id FROM booking.bookings WHERE id = ?::uuid)", id);
        jdbc.update("UPDATE booking.bookings SET hold_expires_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
        jdbc.update("UPDATE booking.booking_sagas SET next_retry_at = now() - interval '1 second' WHERE booking_id = ?::uuid", id);
    }
}
