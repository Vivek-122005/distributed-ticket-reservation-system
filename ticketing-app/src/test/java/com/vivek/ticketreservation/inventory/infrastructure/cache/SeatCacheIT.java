package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery.SeatView;
import com.vivek.ticketreservation.inventory.application.ExpireHoldsJob;
import com.vivek.ticketreservation.inventory.domain.SeatStatus;
import com.vivek.ticketreservation.inventory.infrastructure.cache.SeatStateCache.CachedSeat;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryTestData;
import com.vivek.ticketreservation.support.RedisContainerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;

/** P2.3: seat maps from Redis, kept current by versioned events, with Postgres as the fallback. */
@IntegrationTest
@Import(RedisContainerConfig.class)
@TestPropertySource(properties = "inventory.seat-cache.enabled=true")
class SeatCacheIT {

    @Autowired SeatMapQuery seatMaps;
    @Autowired InventoryApi inventory;
    @Autowired ExpireHoldsJob expiry;
    @Autowired SeatStateCache cache;
    @Autowired SeatCacheUpdater updater;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;
    @Autowired @Qualifier("redis") GenericContainer<?> redisContainer;

    @BeforeEach
    void reset() {
        InventoryTestData.reset(jdbc);
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void firstReadWarmsTheCacheAndLaterReadsComeFromRedis() {
        assertThat(seat("A1")).isEqualTo("AVAILABLE");
        assertThat(redis.hasKey(SeatStateCache.readyKey(DEMO_SHOW))).isTrue();
        assertThat(redis.opsForHash().size(SeatStateCache.key(DEMO_SHOW))).isEqualTo(100);

        // Edit only the cache: the map follows Redis, proving Postgres is not read.
        redis.opsForHash().put(SeatStateCache.key(DEMO_SHOW), "A1", "999|B|0");
        assertThat(seat("A1")).as("served from Redis, not Postgres").isEqualTo("BOOKED");
    }

    @Test
    void holdConfirmReleaseAndExpiryReachTheCacheThroughEvents() throws Exception {
        seat("A1");   // warm
        UUID user = UUID.randomUUID();
        HoldResult confirmed = inventory.hold(new HoldCommand(DEMO_SHOW, user, List.of("A1")));
        HoldResult released = inventory.hold(new HoldCommand(DEMO_SHOW, user, List.of("A2")));
        HoldResult expired = inventory.hold(new HoldCommand(DEMO_SHOW, user, List.of("A3")));
        settle();
        assertThat(seat("A1")).isEqualTo("HELD");

        inventory.confirm(confirmed.holdId(), user);
        inventory.release(released.holdId(), user);
        jdbc.update("UPDATE inventory.holds SET expires_at = now() - interval '1 second' WHERE id = ?", expired.holdId());
        jdbc.update("UPDATE inventory.show_seats SET hold_expires_at = now() - interval '1 second' WHERE hold_id = ?", expired.holdId());
        expiry.runBatch(100);
        settle();

        assertThat(seat("A1")).isEqualTo("BOOKED");
        assertThat(seat("A2")).isEqualTo("AVAILABLE");
        assertThat(seat("A3")).isEqualTo("AVAILABLE");
        assertThat(cache.read(DEMO_SHOW).orElseThrow().get("A1").version())
                .as("cache holds the database row version").isEqualTo(version("A1"));
    }

    @Test
    void olderVersionsNeverOverwriteNewerOnes() {
        cache.apply(DEMO_SHOW, List.of(new CachedSeat("A9", SeatStatus.BOOKED, null, 7)));

        long applied = cache.apply(DEMO_SHOW, List.of(new CachedSeat("A9", SeatStatus.AVAILABLE, null, 5)));

        assertThat(applied).isZero();
        assertThat(redis.opsForHash().get(SeatStateCache.key(DEMO_SHOW), "A9")).isEqualTo("7|B|0");
    }

    @Test
    void partialHashIsNotServedUntilWarmedButEventsBeforeWarmupSurvive() {
        cache.apply(DEMO_SHOW, List.of(new CachedSeat("A5", SeatStatus.BOOKED, null, 50)));   // event before any read
        assertThat(cache.read(DEMO_SHOW)).as("not ready: never serve a half-filled hash").isEmpty();

        assertThat(seat("A5")).as("first read warms from Postgres (version 0 there)").isEqualTo("AVAILABLE");
        assertThat(cache.read(DEMO_SHOW).orElseThrow().get("A5").status())
                .as("the newer event is kept: warm-up never moves a seat backwards").isEqualTo(SeatStatus.BOOKED);
    }

    @Test
    void expiredHoldIsShownAvailableFromTheCacheToo() {
        seat("A1");
        cache.apply(DEMO_SHOW, List.of(new CachedSeat("A20", SeatStatus.HELD, Instant.now().minusSeconds(5), 1_000)));

        assertThat(seat("A20")).isEqualTo("AVAILABLE");
    }

    @Test
    void redisDownFallsBackToPostgres() {
        inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A30")));
        double before = registry.counter("seat.map.reads", "source", "database").count();
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            assertThat(seat("A30")).isEqualTo("HELD");
            assertThat((System.nanoTime() - start) / 1_000_000).as("bounded by the 250 ms Redis timeout").isLessThan(2_000);
        } finally {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
        assertThat(registry.counter("seat.map.reads", "source", "database").count()).isEqualTo(before + 1);
    }

    private String seat(String code) {
        return seatMaps.seatMap(DEMO_SHOW, Optional.empty()).seats().stream()
                .filter(s -> s.seatCode().equals(code)).map(SeatView::status).findFirst().orElseThrow();
    }

    private void settle() throws InterruptedException { assertThat(updater.drain(5_000)).isTrue(); }

    private long version(String code) {
        return jdbc.queryForObject("SELECT version FROM inventory.show_seats WHERE show_id = ? AND seat_code = ?", Long.class, DEMO_SHOW, code);
    }
}
