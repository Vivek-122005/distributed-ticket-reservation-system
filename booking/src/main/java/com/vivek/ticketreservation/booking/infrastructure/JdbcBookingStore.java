package com.vivek.ticketreservation.booking.infrastructure;

import com.vivek.ticketreservation.booking.application.port.BookingStore;
import com.vivek.ticketreservation.booking.domain.Booking;
import com.vivek.ticketreservation.booking.domain.BookingSeat;
import com.vivek.ticketreservation.booking.domain.BookingStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link BookingStore} over {@code booking.bookings} and {@code booking.booking_seats}.
 * Pattern: Repository (docs/lld/DESIGN_PATTERNS.md#2). Status changes are version-checked (optimistic lock).
 */
@Repository
class JdbcBookingStore implements BookingStore {
    private static final String COLUMNS = "b.id, b.user_id, b.show_id, b.hold_id, b.status, b.amount_minor, b.currency, "
            + "b.hold_expires_at, b.created_at, b.version, s.seat_code, s.price_minor";
    private final NamedParameterJdbcTemplate jdbc;

    JdbcBookingStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public Booking insert(Booking booking) {
        Timestamp createdAt = jdbc.queryForObject("""
                INSERT INTO booking.bookings (id, user_id, show_id, hold_id, status, amount_minor, currency, hold_expires_at)
                VALUES (:id, :user, :show, :hold, :status, :amount, :currency, :expires)
                RETURNING created_at""",
                new MapSqlParameterSource().addValue("id", booking.id()).addValue("user", booking.userId())
                        .addValue("show", booking.showId()).addValue("hold", booking.holdId())
                        .addValue("status", booking.status().name()).addValue("amount", booking.amountMinor())
                        .addValue("currency", booking.currency())
                        .addValue("expires", Timestamp.from(booking.holdExpiresAt())), Timestamp.class);
        MapSqlParameterSource[] lines = booking.seats().stream().map(s -> new MapSqlParameterSource()
                .addValue("id", booking.id()).addValue("code", s.seatCode()).addValue("price", s.priceMinor()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("INSERT INTO booking.booking_seats (booking_id, seat_code, price_minor) VALUES (:id, :code, :price)", lines);
        return new Booking(booking.id(), booking.userId(), booking.showId(), booking.holdId(), booking.status(),
                booking.seats(), booking.amountMinor(), booking.currency(), booking.holdExpiresAt(),
                createdAt.toInstant(), booking.version());
    }

    @Override
    public Optional<Booking> find(UUID bookingId) {
        return query("WHERE b.id = :id", new MapSqlParameterSource("id", bookingId), "").stream().findFirst();
    }

    @Override
    public boolean updateStatus(Booking booking) {
        return jdbc.update("""
                UPDATE booking.bookings SET status = :status, version = version + 1, updated_at = now()
                WHERE id = :id AND version = :version""",
                new MapSqlParameterSource().addValue("status", booking.status().name())
                        .addValue("id", booking.id()).addValue("version", booking.version())) == 1;
    }

    /** One joined row: a booking's columns plus one of its seat lines. */
    private record Row(UUID id, UUID userId, UUID showId, UUID holdId, BookingStatus status, long amountMinor,
                       String currency, Instant holdExpiresAt, Instant createdAt, long version, BookingSeat seat) { }

    private List<Booking> query(String where, MapSqlParameterSource params, String orderPrefix) {
        Map<UUID, List<Row>> byBooking = new LinkedHashMap<>();
        jdbc.query("SELECT " + COLUMNS + " FROM booking.bookings b JOIN booking.booking_seats s ON s.booking_id = b.id "
                + where + " ORDER BY " + orderPrefix + "s.seat_code", params, rs -> {
            Row row = new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                    rs.getObject("show_id", UUID.class), rs.getObject("hold_id", UUID.class),
                    BookingStatus.valueOf(rs.getString("status")), rs.getLong("amount_minor"), rs.getString("currency"),
                    rs.getTimestamp("hold_expires_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                    rs.getLong("version"), new BookingSeat(rs.getString("seat_code"), rs.getLong("price_minor")));
            byBooking.computeIfAbsent(row.id(), k -> new ArrayList<>()).add(row);
        });
        List<Booking> result = new ArrayList<>(byBooking.size());
        for (List<Row> lines : byBooking.values()) {
            Row r = lines.get(0);
            result.add(new Booking(r.id(), r.userId(), r.showId(), r.holdId(), r.status(),
                    lines.stream().map(Row::seat).toList(), r.amountMinor(), r.currency(), r.holdExpiresAt(),
                    r.createdAt(), r.version()));
        }
        return result;
    }
}
