package com.vivek.ticketreservation.inventory.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Creates hold rows with the expiry computed by the database clock.
 *
 * <p>SRS assumption A3: replica clocks may skew, so the database clock is the single reference
 * for expiry. {@code now()} is the transaction start time, so the hold row, its seats and the lazy
 * expiry check of the claim (P1.2) all use the same instant.
 */
@Repository
public class JdbcHoldRepository {

    private static final String INSERT = """
            INSERT INTO inventory.holds (id, show_id, user_id, status, expires_at)
            VALUES (:id, :showId, :userId, 'HELD', now() + make_interval(secs => :ttlSeconds))
            RETURNING expires_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcHoldRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Inserts a HELD hold and returns its database-computed expiry. */
    public Instant insertHeld(UUID id, UUID showId, UUID userId, Duration ttl) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id).addValue("showId", showId).addValue("userId", userId)
                .addValue("ttlSeconds", (double) ttl.toSeconds());
        return jdbc.queryForObject(INSERT, params, (rs, i) -> rs.getTimestamp("expires_at").toInstant());
    }
}
