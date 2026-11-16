package com.vivek.ticketreservation.inventory.infrastructure.locking;

import com.vivek.ticketreservation.inventory.application.port.SeatLockingStrategy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link SeatLockingStrategy} (c): one batched conditional update (P1.1). The default strategy; created by
 * {@link SeatLockingConfig}.
 *
 * <p>Pattern: Compare-and-Set, batched form (docs/lld/DESIGN_PATTERNS.md#3). The CTE locks the
 * claimable rows in {@code seat_code} order ({@code ORDER BY … FOR UPDATE}), so two overlapping
 * multi-seat requests always acquire row locks in the same order and cannot deadlock. Under READ
 * COMMITTED, a row that another transaction claimed while we waited is re-checked and dropped, so it
 * simply is not returned.
 *
 * <p>Lazy expiry (P1.2): a seat whose hold has passed {@code hold_expires_at} is claimable at once,
 * judged by the database clock ({@code now()}), so correctness never depends on the expiry worker
 * (P2.1) being on time. The previous hold row keeps its status until that worker marks it EXPIRED;
 * it can no longer be confirmed because confirmation also requires {@code expires_at >= now()} (P1.4),
 * and the worker releases seats only {@code WHERE hold_id = <expired hold>}, so it never touches a
 * seat that has been re-held.
 *
 * <p>Plain JDBC instead of Spring Data: {@code UPDATE … RETURNING} gives the claimed seats and
 * their prices in the same round trip, which a {@code @Modifying} query cannot return.
 */
class ConditionalUpdateStrategy implements SeatLockingStrategy {

    private static final String CLAIM = """
            WITH target AS (
                SELECT id FROM inventory.show_seats
                 WHERE show_id = :showId AND seat_code = ANY(:seatCodes)
                   AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at < now()))
                 ORDER BY seat_code
                   FOR UPDATE)
            UPDATE inventory.show_seats s
               SET status = 'HELD', hold_id = :holdId, held_by_user_id = :userId,
                   hold_expires_at = :expiresAt, version = s.version + 1
              FROM target
             WHERE s.id = target.id
            RETURNING s.seat_code, s.price_minor, s.version
            """;

    private final NamedParameterJdbcTemplate jdbc;

    ConditionalUpdateStrategy(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<ClaimedSeat> claim(UUID showId, List<String> seatCodes, UUID holdId, UUID userId, Instant expiresAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("showId", showId)
                .addValue("seatCodes", seatCodes.toArray(String[]::new))
                .addValue("holdId", holdId)
                .addValue("userId", userId)
                .addValue("expiresAt", Timestamp.from(expiresAt));
        return jdbc.query(CLAIM, params, (rs, i) -> new ClaimedSeat(rs.getString("seat_code"), rs.getLong("price_minor"), rs.getLong("version")));
    }
}
