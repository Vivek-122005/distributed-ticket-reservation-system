package com.vivek.ticketreservation.common.idempotency;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Postgres adapter of {@link IdempotencyStore}. Each module passes its <b>own</b> table (for example
 * {@code booking.idempotency_keys}), so keys stay inside the module that owns the data (ADR 002).
 *
 * <p>The claim is one statement: {@code INSERT … ON CONFLICT DO UPDATE … WHERE <expired or lease
 * lapsed> RETURNING}. Of N concurrent requests with one key, exactly one gets a row back; the
 * others read the existing row and learn whether to replay, wait or reject. No lock is held while
 * the request itself runs.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {
    private static final Pattern TABLE = Pattern.compile("[a-z_]+\\.[a-z_]+");
    private final NamedParameterJdbcTemplate jdbc;
    private final String table;

    public JdbcIdempotencyStore(NamedParameterJdbcTemplate jdbc, String table) {
        if (!TABLE.matcher(table).matches()) throw new IllegalArgumentException("table must be schema.name: " + table);
        this.jdbc = jdbc;
        this.table = table;
    }

    @Override
    public Outcome begin(UUID userId, String key, String requestHash, Duration ttl, Duration lease) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("user", userId).addValue("key", key)
                .addValue("hash", requestHash).addValue("ttl", ttl.toSeconds()).addValue("lease", lease.toSeconds());
        for (int attempt = 0; attempt < 3; attempt++) {
            List<Integer> claimed = jdbc.queryForList("""
                    INSERT INTO %1$s AS t (user_id, idem_key, request_hash, status, expires_at)
                    VALUES (:user, :key, :hash, 'IN_PROGRESS', now() + make_interval(secs => :ttl))
                    ON CONFLICT (user_id, idem_key) DO UPDATE
                       SET request_hash = EXCLUDED.request_hash, status = 'IN_PROGRESS', response_status = NULL,
                           response_content_type = NULL, response_location = NULL, response_body = NULL,
                           created_at = now(), updated_at = now(), expires_at = EXCLUDED.expires_at
                     WHERE t.expires_at < now()
                        OR (t.status = 'IN_PROGRESS' AND t.request_hash = EXCLUDED.request_hash
                            AND t.updated_at < now() - make_interval(secs => :lease))
                    RETURNING 1""".formatted(table), params, Integer.class);
            if (!claimed.isEmpty()) return new Outcome.Started();

            List<Outcome> existing = jdbc.query("""
                    SELECT request_hash, status, response_status, response_content_type, response_location, response_body
                    FROM %s WHERE user_id = :user AND idem_key = :key""".formatted(table), params, (rs, n) -> {
                if (!rs.getString("request_hash").equals(requestHash)) return new Outcome.Mismatch();
                if (!"COMPLETED".equals(rs.getString("status"))) return new Outcome.InProgress();
                return new Outcome.Completed(new StoredResponse(rs.getInt("response_status"),
                        rs.getString("response_content_type"), rs.getString("response_location"), rs.getBytes("response_body")));
            });
            if (!existing.isEmpty()) return existing.get(0);
            // The row vanished between the two statements (abandoned by its owner): try to claim again.
        }
        return new Outcome.InProgress();
    }

    @Override
    public void complete(UUID userId, String key, StoredResponse response) {
        jdbc.update("""
                UPDATE %s SET status = 'COMPLETED', response_status = :status, response_content_type = :type,
                              response_location = :location, response_body = :body, updated_at = now()
                WHERE user_id = :user AND idem_key = :key""".formatted(table),
                new MapSqlParameterSource().addValue("user", userId).addValue("key", key)
                        .addValue("status", response.status()).addValue("type", response.contentType())
                        .addValue("location", response.location()).addValue("body", response.body()));
    }

    @Override
    public void abandon(UUID userId, String key) {
        jdbc.update("DELETE FROM %s WHERE user_id = :user AND idem_key = :key AND status = 'IN_PROGRESS'".formatted(table),
                new MapSqlParameterSource().addValue("user", userId).addValue("key", key));
    }

    @Override
    public int purgeExpired(int limit) {
        return jdbc.update("""
                DELETE FROM %1$s WHERE (user_id, idem_key) IN
                  (SELECT user_id, idem_key FROM %1$s WHERE expires_at < now() LIMIT :limit)""".formatted(table),
                new MapSqlParameterSource("limit", limit));
    }
}
