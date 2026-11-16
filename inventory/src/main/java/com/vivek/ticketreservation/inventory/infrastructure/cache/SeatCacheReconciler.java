package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.infrastructure.cache.SeatStateCache.CachedSeat;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compares the Redis seat cache with Postgres and repairs drift; Postgres wins (P2.5, ADR 001).
 *
 * <p>Drift happens because cache updates are best-effort: an event lost in a crash (in-process events
 * until the outbox, P5.2), an update dropped from a full queue, a Redis restart, a manual database fix.
 * Repairs never fight live traffic:
 * <ul>
 *   <li><b>missing / stale</b> (no entry, or older version than Postgres): written through the
 *       version-guarded {@code APPLY}, so a newer event that lands meanwhile still wins.</li>
 *   <li><b>diverged</b> (same version, different state, for example a pending mark whose request
 *       died, or a corrupted value): replaced only if the entry is still exactly what was read
 *       (compare-and-set), so a concurrent update is never overwritten.</li>
 *   <li>cache <b>ahead</b> of the snapshot (an event landed after our read) and <b>fresh pending</b>
 *       marks are in-flight work, not drift, and are left alone.</li>
 * </ul>
 */
class SeatCacheReconciler {

    enum Drift { MISSING, STALE, DIVERGED }

    record Report(UUID showId, int checked, Map<Drift, Integer> repaired) {
        int total() { return repaired.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    /** Sets ARGV[2] only if the field still holds ARGV[1]. */
    static final RedisScript<Long> COMPARE_AND_SET = RedisScript.of("""
            local changed = 0
            for i = 1, #ARGV, 3 do
              if redis.call('HGET', KEYS[1], ARGV[i]) == ARGV[i + 1] then
                redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 2]); changed = changed + 1
              end
            end
            return changed
            """, Long.class);

    private final SeatStateCache cache;
    private final SeatSnapshots snapshots;
    private final StringRedisTemplate redis;
    private final NamedParameterJdbcTemplate jdbc;
    private final Map<Drift, Counter> drift = new EnumMap<>(Drift.class);

    SeatCacheReconciler(SeatStateCache cache, SeatSnapshots snapshots, StringRedisTemplate redis,
                        NamedParameterJdbcTemplate jdbc, MeterRegistry registry) {
        this.cache = cache; this.snapshots = snapshots; this.redis = redis; this.jdbc = jdbc;
        for (Drift d : Drift.values()) {
            drift.put(d, Counter.builder("inventory.drift").description("Seat cache entries repaired from Postgres")
                    .tag("kind", d.name().toLowerCase()).register(registry));
        }
    }

    /** Shows that are on sale and currently cached. */
    List<UUID> cachedShowsOnSale() {
        return jdbc.queryForList("SELECT id FROM inventory.shows WHERE status IN ('ON_SALE', 'SOLD_OUT') ORDER BY id",
                new MapSqlParameterSource(), UUID.class).stream()
                .filter(id -> Boolean.TRUE.equals(redis.hasKey(SeatStateCache.readyKey(id))))
                .toList();
    }

    Report reconcile(UUID showId) {
        Map<String, String> raw = new java.util.HashMap<>();
        redis.<String, String>opsForHash().entries(SeatStateCache.key(showId)).forEach((k, v) -> raw.put(String.valueOf(k), String.valueOf(v)));
        List<CachedSeat> truth = snapshots.states(showId);
        Instant now = Instant.now();

        List<CachedSeat> toApply = new ArrayList<>();
        List<String> casArgs = new ArrayList<>();
        Map<Drift, Integer> repaired = new EnumMap<>(Drift.class);
        for (CachedSeat db : truth) {
            String value = raw.get(db.seatCode());
            if (value == null) { toApply.add(db); repaired.merge(Drift.MISSING, 1, Integer::sum); continue; }
            CachedSeat cached = SeatStateCache.decode(db.seatCode(), value);
            boolean pending = value.contains("|P|");
            if (cached.version() < db.version()) { toApply.add(db); repaired.merge(Drift.STALE, 1, Integer::sum); }
            else if (cached.version() == db.version() && !sameState(cached, db)
                     && !(pending && cached.holdExpiresAt() != null && cached.holdExpiresAt().isAfter(now))) {
                casArgs.add(db.seatCode()); casArgs.add(value); casArgs.add(SeatStateCache.encode(db));
                repaired.merge(Drift.DIVERGED, 1, Integer::sum);
            }
            // cached.version() > db.version(): a newer event arrived after our read; not drift.
        }
        cache.apply(showId, toApply);
        if (!casArgs.isEmpty()) redis.execute(COMPARE_AND_SET, List.of(SeatStateCache.key(showId)), casArgs.toArray());
        repaired.forEach((kind, n) -> drift.get(kind).increment(n));
        return new Report(showId, truth.size(), repaired);
    }

    private static boolean sameState(CachedSeat a, CachedSeat b) {
        return a.status() == b.status() && java.util.Objects.equals(
                a.holdExpiresAt() == null ? null : a.holdExpiresAt().toEpochMilli(),
                b.holdExpiresAt() == null ? null : b.holdExpiresAt().toEpochMilli());
    }
}
