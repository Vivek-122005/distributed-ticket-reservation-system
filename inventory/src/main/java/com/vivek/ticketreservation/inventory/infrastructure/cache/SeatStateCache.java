package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.domain.SeatStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis copy of seat states per show (P2.3). Postgres stays the authority (ADR 001); this is a cache.
 *
 * <p>Layout: hash {@code seatstate:{showId}} with field = seat code, value = {@code version|S|expiryMillis}
 * ({@code S} = A available, H held, B booked); key {@code seatstate:{showId}:ready} once fully loaded.
 * The braces are a Redis Cluster hash tag, so both keys of a show live in one slot and one script can
 * touch them (P7.2).
 *
 * <p>Order independence: every write goes through {@link #APPLY}, which keeps a seat's entry only if the
 * incoming row version is higher. Events applied late or twice, or a warm-up snapshot older than an
 * event already applied, can therefore never move a seat backwards. Readers ignore a show until its
 * ready marker exists, so a half-loaded hash is never served.
 */
class SeatStateCache {

    /** One seat as cached. {@code holdExpiresAt} only for HELD. */
    record CachedSeat(String seatCode, SeatStatus status, Instant holdExpiresAt, long version) {
        SeatStatus effectiveStatus(Instant now) {   // same lazy-expiry rule as the claim (P1.2)
            return status == SeatStatus.HELD && holdExpiresAt != null && holdExpiresAt.isBefore(now) ? SeatStatus.AVAILABLE : status;
        }
    }

    static final RedisScript<Long> APPLY = RedisScript.of("""
            local applied = 0
            for i = 1, #ARGV, 2 do
              local incoming = tonumber(string.match(ARGV[i + 1], '^(%d+)'))
              local current = redis.call('HGET', KEYS[1], ARGV[i])
              if (not current) or tonumber(string.match(current, '^(%d+)')) < incoming then
                redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
                applied = applied + 1
              end
            end
            return applied
            """, Long.class);

    @SuppressWarnings("rawtypes")
    static final RedisScript<List> READ = RedisScript.of("""
            if redis.call('EXISTS', KEYS[2]) == 0 then return {} end
            return redis.call('HGETALL', KEYS[1])
            """, List.class);

    private final StringRedisTemplate redis;

    SeatStateCache(StringRedisTemplate redis) { this.redis = redis; }

    static String key(UUID showId) { return "seatstate:{" + showId + "}"; }
    static String readyKey(UUID showId) { return "seatstate:{" + showId + "}:ready"; }

    /** Applies seat states, keeping only newer versions. @return how many entries changed */
    long apply(UUID showId, List<CachedSeat> seats) {
        if (seats.isEmpty()) return 0;
        List<String> args = new ArrayList<>(seats.size() * 2);
        for (CachedSeat seat : seats) { args.add(seat.seatCode()); args.add(encode(seat)); }
        Long applied = redis.execute(APPLY, List.of(key(showId)), args.toArray());
        return applied == null ? 0 : applied;
    }

    /** Loads a full snapshot from Postgres and only then marks the show as ready to be served. */
    void warm(UUID showId, List<CachedSeat> snapshot) {
        apply(showId, snapshot);
        redis.opsForValue().set(readyKey(showId), "1");
    }

    /** Current states of a show, or empty if the show is not (fully) cached. */
    @SuppressWarnings("unchecked")
    Optional<Map<String, CachedSeat>> read(UUID showId) {
        List<Object> flat = redis.execute(READ, List.of(key(showId), readyKey(showId)));
        // Not ready comes back as an empty array (a Lua false/nil would arrive as [null]; treat it alike).
        if (flat == null || flat.isEmpty() || flat.get(0) == null) return Optional.empty();
        Map<String, CachedSeat> seats = new HashMap<>(flat.size() / 2);
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            String code = String.valueOf(flat.get(i));
            seats.put(code, decode(code, String.valueOf(flat.get(i + 1))));
        }
        return Optional.of(seats);
    }

    /** Drops a show from the cache, for example when reconciliation finds it beyond repair (P2.5). */
    void evict(UUID showId) { redis.delete(List.of(key(showId), readyKey(showId))); }

    static String encode(CachedSeat seat) {
        String code = switch (seat.status()) { case AVAILABLE -> "A"; case HELD -> "H"; case BOOKED -> "B"; };
        long expiry = seat.holdExpiresAt() == null ? 0 : seat.holdExpiresAt().toEpochMilli();
        return seat.version() + "|" + code + "|" + expiry;
    }

    static CachedSeat decode(String seatCode, String value) {
        String[] parts = value.split("\\|");
        SeatStatus status = switch (parts[1]) { case "H" -> SeatStatus.HELD; case "B" -> SeatStatus.BOOKED; default -> SeatStatus.AVAILABLE; };
        long expiry = Long.parseLong(parts[2]);
        return new CachedSeat(seatCode, status, expiry == 0 ? null : Instant.ofEpochMilli(expiry), Long.parseLong(parts[0]));
    }
}
