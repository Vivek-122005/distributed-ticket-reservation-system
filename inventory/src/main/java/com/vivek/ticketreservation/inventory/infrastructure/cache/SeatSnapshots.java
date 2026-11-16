package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.domain.SeatStatus;
import com.vivek.ticketreservation.inventory.infrastructure.cache.SeatStateCache.CachedSeat;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** Reads from Postgres what the cache needs: the immutable layout of a show and its current seat states. */
class SeatSnapshots {

    /** Everything about a seat that never changes after the show is created. */
    record LayoutSeat(UUID seatId, String seatCode, String section, int row, int col, long priceMinor) { }

    private final NamedParameterJdbcTemplate jdbc;

    SeatSnapshots(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    List<LayoutSeat> layout(UUID showId) {
        return jdbc.query("""
                SELECT ss.seat_id, ss.seat_code, ss.section_code, s.row_no, s.col_no, ss.price_minor
                  FROM inventory.show_seats ss JOIN inventory.seats s ON s.id = ss.seat_id
                 WHERE ss.show_id = :showId
                 ORDER BY ss.section_code, s.row_no, s.col_no
                """, new MapSqlParameterSource("showId", showId),
                (rs, i) -> new LayoutSeat(rs.getObject("seat_id", UUID.class), rs.getString("seat_code"), rs.getString("section_code"),
                        rs.getInt("row_no"), rs.getInt("col_no"), rs.getLong("price_minor")));
    }

    List<CachedSeat> states(UUID showId) {
        return jdbc.query("SELECT seat_code, status, hold_expires_at, version FROM inventory.show_seats WHERE show_id = :showId",
                new MapSqlParameterSource("showId", showId), (rs, i) -> {
                    Timestamp expiry = rs.getTimestamp("hold_expires_at");
                    return new CachedSeat(rs.getString("seat_code"), SeatStatus.valueOf(rs.getString("status")),
                            expiry == null ? null : expiry.toInstant(), rs.getLong("version"));
                });
    }
}
