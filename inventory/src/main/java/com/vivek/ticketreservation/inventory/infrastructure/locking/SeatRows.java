package com.vivek.ticketreservation.inventory.infrastructure.locking;

import com.vivek.ticketreservation.inventory.application.port.SeatLockingStrategy.ClaimedSeat;
import com.vivek.ticketreservation.inventory.domain.SeatStatus;
import com.vivek.ticketreservation.inventory.domain.ShowSeat;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SQL shared by the load-then-update strategies (pessimistic, optimistic, Redis lock): load seats into
 * the {@link ShowSeat} domain object together with the database clock, let the domain decide, write back.
 */
final class SeatRows {

    /** A loaded seat plus the columns the strategies compare on. */
    record Row(ShowSeat seat, long version, long lockFence) { }

    /** Rows of the requested seats and the database's {@code now()} (transaction time). */
    record Snapshot(List<Row> rows, Instant now) {
        boolean allClaimable() { return rows.stream().allMatch(r -> r.seat().isClaimable(now)); }
    }

    private final NamedParameterJdbcTemplate jdbc;

    SeatRows(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    Snapshot load(UUID showId, List<String> seatCodes, boolean forUpdate) {
        MapSqlParameterSource params = new MapSqlParameterSource("showId", showId)
                .addValue("seatCodes", seatCodes.toArray(String[]::new));
        List<Row> rows = jdbc.query("""
                SELECT id, seat_code, price_minor, status, hold_id, hold_expires_at, version, lock_fence
                  FROM inventory.show_seats
                 WHERE show_id = :showId AND seat_code = ANY(:seatCodes)
                 ORDER BY seat_code
                """ + (forUpdate ? " FOR UPDATE" : ""), params, (rs, i) -> {
            Timestamp expiry = rs.getTimestamp("hold_expires_at");
            ShowSeat seat = new ShowSeat(rs.getObject("id", UUID.class), rs.getString("seat_code"), rs.getLong("price_minor"),
                    SeatStatus.valueOf(rs.getString("status")), rs.getObject("hold_id", UUID.class),
                    expiry == null ? null : expiry.toInstant());
            return new Row(seat, rs.getLong("version"), rs.getLong("lock_fence"));
        });
        Instant now = jdbc.queryForObject("SELECT now()", new MapSqlParameterSource(), Instant.class);
        return new Snapshot(rows, now);
    }

    /** Writes HELD for the given rows, guarded by {@code extraCondition}; returns the seats actually written. */
    List<ClaimedSeat> markHeld(List<UUID> ids, UUID holdId, UUID userId, Instant expiresAt, String extraCondition,
                               MapSqlParameterSource extraParams) {
        MapSqlParameterSource params = extraParams
                .addValue("ids", ids.toArray(UUID[]::new)).addValue("holdId", holdId)
                .addValue("userId", userId).addValue("expiresAt", Timestamp.from(expiresAt));
        return jdbc.query("""
                UPDATE inventory.show_seats
                   SET status = 'HELD', hold_id = :holdId, held_by_user_id = :userId,
                       hold_expires_at = :expiresAt, version = version + 1 %s
                 WHERE id = ANY(:ids) %s
                RETURNING seat_code, price_minor, version
                """.formatted(extraParams.hasValue("token") ? ", lock_fence = :token" : "", extraCondition),
                params, (rs, i) -> new ClaimedSeat(rs.getString("seat_code"), rs.getLong("price_minor"), rs.getLong("version")));
    }

    /** Highest fencing token ever written for this show (0 if none): the floor for a re-created counter. */
    long maxFence(UUID showId) {
        Long max = jdbc.queryForObject("SELECT coalesce(max(lock_fence), 0) FROM inventory.show_seats WHERE show_id = :showId",
                new MapSqlParameterSource("showId", showId), Long.class);
        return max == null ? 0 : max;
    }

    /** Seats currently owned by this hold (used to report what an optimistic retry loop ended with). */
    List<ClaimedSeat> ownedBy(UUID holdId) {
        return jdbc.query("SELECT seat_code, price_minor, version FROM inventory.show_seats WHERE hold_id = :holdId AND status = 'HELD'",
                new MapSqlParameterSource("holdId", holdId),
                (rs, i) -> new ClaimedSeat(rs.getString("seat_code"), rs.getLong("price_minor"), rs.getLong("version")));
    }
}
