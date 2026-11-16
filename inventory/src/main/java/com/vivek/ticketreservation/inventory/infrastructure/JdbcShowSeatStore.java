package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.application.port.ShowSeatStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
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
    public List<SeatVersion> bookSeatsOf(UUID holdId) {
        return jdbc.query("""
                UPDATE inventory.show_seats SET status = 'BOOKED', version = version + 1
                 WHERE hold_id = :holdId AND status = 'HELD'
                RETURNING seat_code, version
                """, new MapSqlParameterSource("holdId", holdId),
                (rs, i) -> new SeatVersion(rs.getString("seat_code"), rs.getLong("version")));
    }

    @Override
    public Map<UUID, List<SeatVersion>> releaseSeatsOfHolds(List<UUID> holdIds) {
        if (holdIds.isEmpty()) return Map.of();
        MapSqlParameterSource params = new MapSqlParameterSource("holdIds", holdIds.toArray(UUID[]::new));
        // Lock first and remember which hold owned which seat: after the UPDATE the hold_id is gone.
        Map<UUID, UUID> ownerBySeatId = new HashMap<>();
        jdbc.query("""
                SELECT id, hold_id FROM inventory.show_seats
                 WHERE hold_id = ANY(:holdIds) AND status = 'HELD'
                 ORDER BY show_id, seat_code
                   FOR UPDATE""", params,
                rs -> { ownerBySeatId.put(rs.getObject("id", UUID.class), rs.getObject("hold_id", UUID.class)); });
        if (ownerBySeatId.isEmpty()) return Map.of();
        Map<UUID, List<SeatVersion>> released = new LinkedHashMap<>();
        jdbc.query("""
                UPDATE inventory.show_seats
                   SET status = 'AVAILABLE', hold_id = NULL, held_by_user_id = NULL, hold_expires_at = NULL,
                       version = version + 1
                 WHERE id = ANY(:ids)
                RETURNING id, seat_code, version
                """, new MapSqlParameterSource("ids", ownerBySeatId.keySet().toArray(UUID[]::new)),
                rs -> {
                    UUID owner = ownerBySeatId.get(rs.getObject("id", UUID.class));
                    released.computeIfAbsent(owner, k -> new ArrayList<>())
                            .add(new SeatVersion(rs.getString("seat_code"), rs.getLong("version")));
                });
        released.values().forEach(list -> list.sort(java.util.Comparator.comparing(SeatVersion::seatCode)));
        return released;
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
