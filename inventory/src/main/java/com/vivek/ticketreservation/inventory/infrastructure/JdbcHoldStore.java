package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.application.port.HoldStore;
import com.vivek.ticketreservation.inventory.domain.Hold;
import com.vivek.ticketreservation.inventory.domain.HoldStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link HoldStore} over {@code inventory.holds} with plain JDBC; maps rows to the framework-free
 * {@link Hold} aggregate (fixes SOLID.md deviation #3: no persistence annotations in the domain).
 *
 * <p>SRS assumption A3: replica clocks may skew, so the database clock is the single reference
 * for expiry. {@code now()} is the transaction start time, so the hold row, its seats and the lazy
 * expiry check of the claim (P1.2) all use the same instant.
 */
@Component
class JdbcHoldStore implements HoldStore {

    private static final String COLUMNS = "id, show_id, user_id, status, expires_at, seat_codes";
    private static final RowMapper<Hold> HOLD = (rs, i) -> new Hold(
            rs.getObject("id", UUID.class), rs.getObject("show_id", UUID.class), rs.getObject("user_id", UUID.class),
            HoldStatus.valueOf(rs.getString("status")), rs.getTimestamp("expires_at").toInstant(),
            Arrays.asList((String[]) rs.getArray("seat_codes").getArray()));

    private final NamedParameterJdbcTemplate jdbc;

    JdbcHoldStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Hold createHeld(UUID id, UUID showId, UUID userId, List<String> seatCodes, Duration ttl) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id).addValue("showId", showId).addValue("userId", userId)
                .addValue("seatCodes", seatCodes.toArray(String[]::new))
                .addValue("ttlSeconds", (double) ttl.toSeconds());
        return jdbc.queryForObject("""
                INSERT INTO inventory.holds (id, show_id, user_id, status, expires_at, seat_codes)
                VALUES (:id, :showId, :userId, 'HELD', now() + make_interval(secs => :ttlSeconds), :seatCodes)
                RETURNING %s
                """.formatted(COLUMNS), params, HOLD);
    }

    @Override
    public Optional<Hold> find(UUID holdId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.holds WHERE id = :id",
                new MapSqlParameterSource("id", holdId), HOLD).stream().findFirst();
    }

    @Override
    public Optional<Hold> lock(UUID holdId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.holds WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", holdId), HOLD).stream().findFirst();
    }

    @Override
    public List<Hold> lockExpired(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.holds"
                + " WHERE status = 'HELD' AND expires_at < now()"
                + " ORDER BY expires_at LIMIT :limit"
                + " FOR UPDATE SKIP LOCKED", new MapSqlParameterSource("limit", limit), HOLD);
    }

    @Override
    public List<UUID> findExpiredIds(int limit) {
        return jdbc.queryForList("SELECT id FROM inventory.holds WHERE status = 'HELD' AND expires_at < now()"
                + " ORDER BY expires_at LIMIT :limit", new MapSqlParameterSource("limit", limit), UUID.class);
    }

    @Override
    public Optional<Hold> lockIfExpired(UUID holdId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.holds"
                + " WHERE id = :id AND status = 'HELD' AND expires_at < now() FOR UPDATE SKIP LOCKED",
                new MapSqlParameterSource("id", holdId), HOLD).stream().findFirst();
    }

    @Override
    public void saveStatuses(List<Hold> holds) {
        if (holds.isEmpty()) return;
        MapSqlParameterSource[] batch = holds.stream()
                .map(h -> new MapSqlParameterSource("status", h.status().name()).addValue("id", h.id()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("UPDATE inventory.holds SET status = :status WHERE id = :id", batch);
    }

    @Override
    public void saveStatus(Hold hold) {
        jdbc.update("UPDATE inventory.holds SET status = :status WHERE id = :id",
                new MapSqlParameterSource("status", hold.status().name()).addValue("id", hold.id()));
    }
}
