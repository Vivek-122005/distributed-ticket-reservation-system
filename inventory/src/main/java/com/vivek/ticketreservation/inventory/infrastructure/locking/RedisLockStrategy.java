package com.vivek.ticketreservation.inventory.infrastructure.locking;

import com.vivek.ticketreservation.inventory.application.port.SeatLockingStrategy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link SeatLockingStrategy} (d): a Redis distributed lock per seat ({@code SET key holdId NX PX}),
 * then a plain read-decide-write in Postgres guarded by a fencing token.
 *
 * <p>Pattern: Fencing token (docs/lld/DESIGN_PATTERNS.md#20). Steps:
 * <ol>
 *   <li>{@code INCR fence:{showId}} gives a token larger than every earlier one.</li>
 *   <li>Lock seats in seat-code order; if any lock is taken, release the ones we got and fail fast.</li>
 *   <li>The locks are released <b>after the database transaction ends</b> (commit or rollback), not when
 *       this method returns: otherwise another client could read the seat before our write commits.</li>
 *   <li>The write is accepted only {@code WHERE lock_fence < :token}, so a client whose lock expired
 *       (GC pause longer than the TTL) is rejected once a newer holder has written.</li>
 * </ol>
 * A lost counter is re-seeded from Postgres ({@link #nextToken}). Known limit (Kleppmann): if our lock expires and a newer holder has <i>not yet</i> written, our stale
 * write still wins and the newer one later overwrites it. Locks are therefore not a safe source of
 * truth on their own; in this system Postgres stays the authority. Measured in P1.8.
 */
class RedisLockStrategy implements SeatLockingStrategy {
    static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            local released = 0
            for i, key in ipairs(KEYS) do
              if redis.call('GET', key) == ARGV[1] then redis.call('DEL', key); released = released + 1 end
            end
            return released
            """, Long.class);

    private final SeatRows rows;
    private final StringRedisTemplate redis;

    RedisLockStrategy(SeatRows rows, StringRedisTemplate redis) { this.rows = rows; this.redis = redis; }

    @Override
    public List<ClaimedSeat> claim(UUID showId, List<String> seatCodes, UUID holdId, UUID userId, Instant expiresAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("RedisLockStrategy must run inside a transaction (locks are released after it)");
        }
        String owner = holdId.toString();
        Long token = nextToken(showId);
        List<String> acquired = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { release(acquired, owner); }
        });
        for (String seat : seatCodes.stream().sorted().toList()) {
            String key = "lock:{" + showId + "}:" + seat;
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, LOCK_TTL))) return List.of();
            acquired.add(key);
        }

        SeatRows.Snapshot snapshot = rows.load(showId, seatCodes, false);
        if (snapshot.rows().size() != seatCodes.size() || !snapshot.allClaimable()) return List.of();
        List<UUID> ids = snapshot.rows().stream().map(r -> r.seat().id()).toList();
        return rows.markHeld(ids, holdId, userId, expiresAt, "AND lock_fence < :token",
                new MapSqlParameterSource("token", token));
    }

    /**
     * Next fencing token. If Redis lost the counter (restart without persistence, failover), a fresh
     * counter would start at 1 and every write would be rejected by the higher tokens already stored:
     * safe but unavailable. So a missing counter is first seeded from the highest token in Postgres.
     */
    private long nextToken(UUID showId) {
        String key = "fence:{" + showId + "}";
        if (!Boolean.TRUE.equals(redis.hasKey(key))) redis.opsForValue().setIfAbsent(key, Long.toString(rows.maxFence(showId)));
        Long token = redis.opsForValue().increment(key);
        if (token == null) throw new IllegalStateException("Redis returned no fencing token");
        return token;
    }

    private void release(List<String> keys, String owner) {
        if (!keys.isEmpty()) redis.execute(RELEASE, keys, owner);   // compare-and-delete: never free another owner's lock
    }
}
