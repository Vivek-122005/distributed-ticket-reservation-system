package com.vivek.ticketreservation.inventory.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hot-path seat claim on {@code inventory.show_seats}: one statement for all requested seats.
 *
 * <p>Pattern: Compare-and-Set, batched form (docs/lld/DESIGN_PATTERNS.md#3). The CTE locks the
 * still-AVAILABLE rows in {@code seat_code} order ({@code ORDER BY … FOR UPDATE}), so two
 * overlapping multi-seat requests always acquire row locks in the same order and cannot deadlock.
 * Under READ COMMITTED, a row that another transaction claimed while we waited is re-checked and
 * dropped, so it simply is not returned. The caller compares the number of claimed rows with the
 * number requested and rolls back on a mismatch (all-or-nothing).
 *
 * <p>Plain JDBC instead of Spring Data: {@code UPDATE … RETURNING} gives the claimed seats and
 * their prices in the same round trip, which a {@code @Modifying} query cannot return.
 */
@Repository
public class JdbcSeatClaimRepository {

    /** A seat that was claimed, with the price frozen on the show seat. */
    public record ClaimedSeat(String seatCode, long priceMinor) { }

    private static final String CLAIM = """
            WITH target AS (
                SELECT id FROM inventory.show_seats
                 WHERE show_id = :showId AND seat_code = ANY(:seatCodes) AND status = 'AVAILABLE'
                 ORDER BY seat_code
                   FOR UPDATE)
            UPDATE inventory.show_seats s
               SET status = 'HELD', hold_id = :holdId, held_by_user_id = :userId,
                   hold_expires_at = :expiresAt, version = s.version + 1
              FROM target
             WHERE s.id = target.id
            RETURNING s.seat_code, s.price_minor
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSeatClaimRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Claims every listed seat that is still AVAILABLE; returns only the seats actually claimed. */
    public List<ClaimedSeat> claimAvailable(UUID showId, List<String> seatCodes, UUID holdId, UUID userId, Instant expiresAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("showId", showId)
                .addValue("seatCodes", seatCodes.toArray(String[]::new))
                .addValue("holdId", holdId)
                .addValue("userId", userId)
                .addValue("expiresAt", Timestamp.from(expiresAt));
        return jdbc.query(CLAIM, params, (rs, i) -> new ClaimedSeat(rs.getString("seat_code"), rs.getLong("price_minor")));
    }
}
