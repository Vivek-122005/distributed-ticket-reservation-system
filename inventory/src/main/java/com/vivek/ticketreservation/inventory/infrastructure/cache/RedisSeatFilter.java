package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.application.port.SeatFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link SeatFilter} over the seat cache (P2.4): one Lua script checks every requested seat and, if all
 * look free, marks them pending ({@code version|P|expiryMs|token}) in the same atomic step.
 *
 * <p>The pending mark is what stops a stampede: of 1,000 requests for one seat, the first marks it and
 * the other 999 are rejected in Redis, so only one reaches Postgres. Marks keep the seat's cached
 * version, so the committed {@code SeatsChanged} (higher version) replaces them; if that never comes,
 * they expire after {@link #PENDING_TTL}.
 *
 * <p>Gives up (SKIPPED) rather than guessing: show not warmed, unknown seat code, any Redis error, or the
 * shared breaker is open (P2.6), in which case Redis is not even called.
 */
class RedisSeatFilter implements SeatFilter {
    private static final Logger log = LoggerFactory.getLogger(RedisSeatFilter.class);
    static final Duration PENDING_TTL = Duration.ofSeconds(2);

    /** Returns 1 reserved, 0 rejected, -1 no opinion. */
    static final RedisScript<Long> RESERVE = RedisScript.of("""
            if redis.call('EXISTS', KEYS[2]) == 0 then return -1 end
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local versions = {}
            for i = 3, #ARGV do
              local v = redis.call('HGET', KEYS[1], ARGV[i])
              if not v then return -1 end
              local version, state, expiry = string.match(v, '^(%d+)|(%a)|(%d+)')
              if state == 'B' then return 0 end
              if (state == 'H' or state == 'P') and tonumber(expiry) > now then return 0 end
              versions[i] = version
            end
            local until_ms = now + tonumber(ARGV[1])
            for i = 3, #ARGV do
              redis.call('HSET', KEYS[1], ARGV[i], versions[i] .. '|P|' .. until_ms .. '|' .. ARGV[2])
            end
            return 1
            """, Long.class);

    /** Turns our own pending marks back into "available" (same version); leaves anything else alone. */
    static final RedisScript<Long> CANCEL = RedisScript.of("""
            local undone = 0
            for i = 2, #ARGV do
              local v = redis.call('HGET', KEYS[1], ARGV[i])
              if v then
                local version, state, expiry, token = string.match(v, '^(%d+)|(%a)|(%d+)|?(.*)$')
                if state == 'P' and token == ARGV[1] then
                  redis.call('HSET', KEYS[1], ARGV[i], version .. '|A|0'); undone = undone + 1
                end
              end
            end
            return undone
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final Map<Verdict, Counter> verdicts = new EnumMap<>(Verdict.class);
    private final Counter errors;

    RedisSeatFilter(StringRedisTemplate redis, RedisGuard guard, MeterRegistry registry) {
        this.redis = redis;
        this.guard = guard;
        for (Verdict v : Verdict.values()) {
            verdicts.put(v, Counter.builder("seat.filter.verdicts").description("Fast-path decisions before Postgres")
                    .tag("verdict", v.name()).register(registry));
        }
        this.errors = Counter.builder("seat.filter.errors").description("Fast path skipped because Redis failed").register(registry);
    }

    @Override
    public Reservation tryReserve(UUID showId, List<String> seatCodes) {
        String token = UUID.randomUUID().toString();
        Reservation result = guard.call(() -> {
            List<String> args = new ArrayList<>(List.of(Long.toString(PENDING_TTL.toMillis()), token));
            args.addAll(seatCodes);
            Long answer = redis.execute(RESERVE, List.of(SeatStateCache.key(showId), SeatStateCache.readyKey(showId)), args.toArray());
            return answer == null || answer < 0 ? Reservation.skipped()
                    : new Reservation(answer == 1 ? Verdict.RESERVED : Verdict.REJECTED, showId, seatCodes, token);
        }, () -> {
            errors.increment();
            log.debug("Fast path skipped for show {} (Redis failed or breaker open)", showId);
            return Reservation.skipped();
        });
        verdicts.get(result.verdict()).increment();
        return result;
    }

    @Override
    public void cancel(Reservation reservation) {
        if (reservation.verdict() != Verdict.RESERVED) return;
        guard.run(() -> {
            List<String> args = new ArrayList<>(List.of(reservation.token()));
            args.addAll(reservation.seatCodes());
            redis.execute(CANCEL, List.of(SeatStateCache.key(reservation.showId())), args.toArray());
        }, () -> { /* the marks time out on their own after PENDING_TTL */ });
    }
}
