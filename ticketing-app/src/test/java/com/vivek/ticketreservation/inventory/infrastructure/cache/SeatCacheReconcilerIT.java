package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery;
import com.vivek.ticketreservation.inventory.domain.SeatStatus;
import com.vivek.ticketreservation.inventory.infrastructure.cache.SeatCacheReconciler.Drift;
import com.vivek.ticketreservation.inventory.infrastructure.cache.SeatStateCache.CachedSeat;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryTestData;
import com.vivek.ticketreservation.support.RedisContainerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;

/** P2.5: drift between the Redis seat cache and Postgres is found and repaired; Postgres wins. */
@IntegrationTest
@Import(RedisContainerConfig.class)
@TestPropertySource(properties = "inventory.seat-cache.enabled=true")
class SeatCacheReconcilerIT {

    @Autowired SeatCacheReconciler reconciler;
    @Autowired SeatStateCache cache;
    @Autowired SeatCacheUpdater updater;
    @Autowired SeatMapQuery seatMaps;
    @Autowired InventoryApi inventory;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;

    @BeforeEach
    void reset() {
        InventoryTestData.reset(jdbc);
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        seatMaps.seatMap(DEMO_SHOW, Optional.empty());   // warm
    }

    @Test
    void consistentCacheNeedsNoRepair() throws Exception {
        inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A1")));
        assertThat(updater.drain(5_000)).isTrue();

        assertThat(reconciler.reconcile(DEMO_SHOW).total()).isZero();
        assertThat(reconciler.cachedShowsOnSale()).containsExactly(DEMO_SHOW);
    }

    @Test
    void missingStaleAndDivergedEntriesAreRepairedFromPostgres() {
        String key = SeatStateCache.key(DEMO_SHOW);
        redis.opsForHash().delete(key, "A1");                                             // missing (e.g. Redis lost a write)
        UUID hold = UUID.randomUUID();                                                    // stale: DB changed, event lost
        jdbc.update("INSERT INTO inventory.holds (id, show_id, user_id, status, expires_at, seat_codes) VALUES (?, ?, ?, 'CONFIRMED', now(), ARRAY['A2'])",
                hold, DEMO_SHOW, UUID.randomUUID());
        jdbc.update("UPDATE inventory.show_seats SET status = 'BOOKED', hold_id = ?, held_by_user_id = ?, hold_expires_at = now(), version = version + 1 "
                + "WHERE show_id = ? AND seat_code = 'A2'", hold, UUID.randomUUID(), DEMO_SHOW);
        redis.opsForHash().put(key, "A3", version("A3") + "|B|0");                        // diverged: same version, wrong state
        double before = registry.counter("inventory.drift", "kind", "stale").count();

        SeatCacheReconciler.Report report = reconciler.reconcile(DEMO_SHOW);

        assertThat(report.repaired()).containsEntry(Drift.MISSING, 1).containsEntry(Drift.STALE, 1).containsEntry(Drift.DIVERGED, 1);
        assertThat(redis.opsForHash().get(key, "A1")).isEqualTo(version("A1") + "|A|0");
        assertThat((String) redis.opsForHash().get(key, "A2")).startsWith(version("A2") + "|B|");
        assertThat(redis.opsForHash().get(key, "A3")).isEqualTo(version("A3") + "|A|0");
        assertThat(registry.counter("inventory.drift", "kind", "stale").count()).isEqualTo(before + 1);
        assertThat(reconciler.reconcile(DEMO_SHOW).total()).as("idempotent").isZero();
    }

    @Test
    void inFlightWorkIsNotTreatedAsDrift() {
        String key = SeatStateCache.key(DEMO_SHOW);
        redis.opsForHash().put(key, "A4", version("A4") + "|P|" + Instant.now().plusSeconds(2).toEpochMilli() + "|token");   // fresh pending mark
        cache.apply(DEMO_SHOW, List.of(new CachedSeat("A5", SeatStatus.HELD, Instant.now().plusSeconds(300), version("A5") + 9)));   // cache ahead

        assertThat(reconciler.reconcile(DEMO_SHOW).total()).isZero();
        assertThat((String) redis.opsForHash().get(key, "A4")).contains("|P|");
        assertThat((String) redis.opsForHash().get(key, "A5")).startsWith((version("A5") + 9) + "|H|");
    }

    @Test
    void abandonedPendingMarkIsCleanedUp() {
        String key = SeatStateCache.key(DEMO_SHOW);
        redis.opsForHash().put(key, "A6", version("A6") + "|P|" + Instant.now().minusSeconds(10).toEpochMilli() + "|dead");

        assertThat(reconciler.reconcile(DEMO_SHOW).repaired()).containsEntry(Drift.DIVERGED, 1);
        assertThat(redis.opsForHash().get(key, "A6")).isEqualTo(version("A6") + "|A|0");
    }

    /** Row versions survive the per-test reset (they only grow), so expectations use the real value. */
    private long version(String seat) {
        return jdbc.queryForObject("SELECT version FROM inventory.show_seats WHERE show_id = ? AND seat_code = ?", Long.class, DEMO_SHOW, seat);
    }
}
