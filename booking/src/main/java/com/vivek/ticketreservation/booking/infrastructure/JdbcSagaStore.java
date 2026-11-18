package com.vivek.ticketreservation.booking.infrastructure;

import com.vivek.ticketreservation.booking.application.port.SagaStore;
import com.vivek.ticketreservation.booking.domain.SagaStep;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link SagaStore} over {@code booking.booking_sagas}. */
@Repository
class JdbcSagaStore implements SagaStore {
    private static final RowMapper<Saga> ROW = (rs, n) -> new Saga(rs.getObject("booking_id", UUID.class),
            SagaStep.valueOf(rs.getString("step")), rs.getInt("attempts"), rs.getTimestamp("next_retry_at").toInstant(),
            rs.getString("last_error"));
    private final NamedParameterJdbcTemplate jdbc;

    JdbcSagaStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void create(UUID bookingId, Instant nextCheckAt) {
        jdbc.update("INSERT INTO booking.booking_sagas (booking_id, step, next_retry_at) VALUES (:id, 'AWAITING_PAYMENT', :next)",
                new MapSqlParameterSource().addValue("id", bookingId).addValue("next", Timestamp.from(nextCheckAt)));
    }

    @Override
    public Optional<Saga> find(UUID bookingId) {
        return jdbc.query("SELECT * FROM booking.booking_sagas WHERE booking_id = :id", new MapSqlParameterSource("id", bookingId), ROW)
                .stream().findFirst();
    }

    @Override
    public void moveTo(UUID bookingId, SagaStep step, Instant nextRetryAt) {
        jdbc.update("""
                UPDATE booking.booking_sagas SET step = :step, attempts = 0, last_error = NULL, next_retry_at = :next, updated_at = now()
                WHERE booking_id = :id""",
                new MapSqlParameterSource().addValue("id", bookingId).addValue("step", step.name())
                        .addValue("next", Timestamp.from(nextRetryAt)));
    }

    @Override
    public void retryLater(UUID bookingId, String error, Instant nextRetryAt) {
        jdbc.update("""
                UPDATE booking.booking_sagas SET attempts = attempts + 1, last_error = left(:error, 500), next_retry_at = :next, updated_at = now()
                WHERE booking_id = :id""",
                new MapSqlParameterSource().addValue("id", bookingId).addValue("error", error)
                        .addValue("next", Timestamp.from(nextRetryAt)));
    }

    @Override
    public List<Saga> claimDue(int limit, Duration lease) {
        return jdbc.query("""
                UPDATE booking.booking_sagas s SET next_retry_at = now() + make_interval(secs => :lease)
                WHERE booking_id IN (SELECT booking_id FROM booking.booking_sagas
                                     WHERE step <> 'DONE' AND next_retry_at <= now()
                                     ORDER BY next_retry_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING s.*""",
                new MapSqlParameterSource().addValue("limit", limit).addValue("lease", lease.toSeconds()), ROW);
    }
}
