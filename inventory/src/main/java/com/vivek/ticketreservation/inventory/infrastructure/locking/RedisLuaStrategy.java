package com.vivek.ticketreservation.inventory.infrastructure.locking;

import com.vivek.ticketreservation.inventory.application.port.SeatLockingStrategy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link SeatLockingStrategy} (e): an atomic multi-seat check-and-set in Redis (one Lua script) as a
 * fast-reject filter, in front of the conditional update in Postgres, which still decides.
 *
 * <p>Pattern: Decorator over {@link ConditionalUpdateStrategy}. Redis keeps {@code seats:{showId}} as a
 * hash {@code seatCode → holdId|expiresAtMillis}. If any requested seat carries a live mark, the request
 * is rejected without touching Postgres; otherwise all marks are set in the same script (all-or-nothing)
 * and the database claim runs. If the transaction does not commit, our marks are removed again.
 *
 * <p>Known limit until the cache is kept in sync (P2.3–P2.5): a release, or an expiry changed in the
 * database, is not reflected in Redis, so that seat is rejected until its mark's own expiry passes.
 * That causes false rejections but never an oversell, because Postgres stays the authority.
 */
class RedisLuaStrategy implements SeatLockingStrategy {
    static final RedisScript<Long> RESERVE = RedisScript.of("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            for i = 3, #ARGV do
              local mark = redis.call('HGET', KEYS[1], ARGV[i])
              if mark then
                local expiry = tonumber(string.match(mark, '|(%d+)$'))
                if expiry and expiry > now then return 0 end
              end
            end
            for i = 3, #ARGV do redis.call('HSET', KEYS[1], ARGV[i], ARGV[1] .. '|' .. ARGV[2]) end
            return 1
            """, Long.class);
    private static final RedisScript<Long> UNRESERVE = RedisScript.of("""
            local removed = 0
            for i = 2, #ARGV do
              local mark = redis.call('HGET', KEYS[1], ARGV[i])
              if mark and string.sub(mark, 1, string.len(ARGV[1]) + 1) == ARGV[1] .. '|' then
                redis.call('HDEL', KEYS[1], ARGV[i]); removed = removed + 1
              end
            end
            return removed
            """, Long.class);

    private final SeatLockingStrategy authority;
    private final StringRedisTemplate redis;

    RedisLuaStrategy(SeatLockingStrategy authority, StringRedisTemplate redis) { this.authority = authority; this.redis = redis; }

    static String key(UUID showId) { return "seats:{" + showId + "}"; }

    @Override
    public List<ClaimedSeat> claim(UUID showId, List<String> seatCodes, UUID holdId, UUID userId, Instant expiresAt) {
        List<String> reserveArgs = new ArrayList<>(List.of(holdId.toString(), Long.toString(expiresAt.toEpochMilli())));
        reserveArgs.addAll(seatCodes);
        Long reserved = redis.execute(RESERVE, List.of(key(showId)), reserveArgs.toArray());
        if (reserved == null || reserved == 0) return List.of();   // fast reject: Postgres never sees it

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    List<String> args = new ArrayList<>(List.of(holdId.toString()));
                    args.addAll(seatCodes);
                    redis.execute(UNRESERVE, List.of(key(showId)), args.toArray());
                }
            }
        });
        return authority.claim(showId, seatCodes, holdId, userId, expiresAt);
    }
}
