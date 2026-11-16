package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.application.port.ShowSeatStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@link ShowSeatStore} over {@code inventory.show_seats}; every write is conditional on the owning hold id. */
@Component
class JdbcShowSeatStore implements ShowSeatStore {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcShowSeatStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public int bookSeatsOf(UUID holdId) {
        return jdbc.update("""
                UPDATE inventory.show_seats SET status = 'BOOKED', version = version + 1
                 WHERE hold_id = :holdId AND status = 'HELD'
                """, new MapSqlParameterSource("holdId", holdId));
    }

    @Override
    public int releaseSeatsOf(UUID holdId) {
        return jdbc.update("""
                UPDATE inventory.show_seats
                   SET status = 'AVAILABLE', hold_id = NULL, held_by_user_id = NULL, hold_expires_at = NULL,
                       version = version + 1
                 WHERE hold_id = :holdId AND status = 'HELD'
                """, new MapSqlParameterSource("holdId", holdId));
    }

    @Override
    public Map<UUID, List<String>> releaseSeatsOfHolds(List<UUID> holdIds) {
        if (holdIds.isEmpty()) return Map.of();
        MapSqlParameterSource params = new MapSqlParameterSource("holdIds", holdIds.toArray(UUID[]::new));
        // Lock first and remember which hold owned which seat: after the UPDATE the hold_id is gone.
        Map<UUID, List<String>> owned = new LinkedHashMap<>();
        jdbc.query("""
                SELECT hold_id, seat_code FROM inventory.show_seats
                 WHERE hold_id = ANY(:holdIds) AND status = 'HELD'
                 ORDER BY show_id, seat_code
                   FOR UPDATE""", params,
                rs -> { owned.computeIfAbsent(rs.getObject("hold_id", UUID.class), k -> new ArrayList<>()).add(rs.getString("seat_code")); });
        jdbc.update("""
                UPDATE inventory.show_seats
                   SET status = 'AVAILABLE', hold_id = NULL, held_by_user_id = NULL, hold_expires_at = NULL,
                       version = version + 1
                 WHERE hold_id = ANY(:holdIds) AND status = 'HELD'
                """, params);
        return owned;
    }

    @Override
    public List<PricedSeat> prices(UUID showId, List<String> seatCodes) {
        return jdbc.query("""
                SELECT seat_code, price_minor FROM inventory.show_seats
                 WHERE show_id = :showId AND seat_code = ANY(:seatCodes)
                 ORDER BY seat_code
                """, new MapSqlParameterSource("showId", showId).addValue("seatCodes", seatCodes.toArray(String[]::new)),
                (rs, i) -> new PricedSeat(rs.getString("seat_code"), rs.getLong("price_minor")));
    }
}
