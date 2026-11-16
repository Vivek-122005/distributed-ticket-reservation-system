package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.application.port.HoldStore;
import com.vivek.ticketreservation.inventory.domain.Hold;
import com.vivek.ticketreservation.inventory.domain.HoldStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    private static final String COLUMNS = "id, show_id, user_id, status, expires_at";
    private static final RowMapper<Hold> HOLD = (rs, i) -> new Hold(
            rs.getObject("id", UUID.class), rs.getObject("show_id", UUID.class), rs.getObject("user_id", UUID.class),
            HoldStatus.valueOf(rs.getString("status")), rs.getTimestamp("expires_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    JdbcHoldStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Hold createHeld(UUID id, UUID showId, UUID userId, Duration ttl) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id).addValue("showId", showId).addValue("userId", userId)
                .addValue("ttlSeconds", (double) ttl.toSeconds());
        return jdbc.queryForObject("""
                INSERT INTO inventory.holds (id, show_id, user_id, status, expires_at)
                VALUES (:id, :showId, :userId, 'HELD', now() + make_interval(secs => :ttlSeconds))
                RETURNING %s
                """.formatted(COLUMNS), params, HOLD);
    }

    @Override
    public Optional<Hold> find(UUID holdId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.holds WHERE id = :id",
                new MapSqlParameterSource("id", holdId), HOLD).stream().findFirst();
    }
}
