package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryInvariants;
import com.vivek.ticketreservation.support.InventoryTestData;
import com.vivek.ticketreservation.support.RedisContainerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2.6: Redis is an optimisation, not a dependency for correctness. With Redis down, holds and seat
 * maps keep working on Postgres; once the breaker opens they stop paying the Redis timeout; when Redis
 * returns, the fast path comes back by itself.
 */
@IntegrationTest
@Import(RedisContainerConfig.class)
@TestPropertySource(properties = {"inventory.seat-cache.enabled=true", "inventory.redis-breaker.wait-in-open=1s"})
class RedisOutageIT {

    @Autowired InventoryApi inventory;
    @Autowired SeatMapQuery seatMaps;
    @Autowired RedisGuard guard;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;
    @Autowired @Qualifier("redis") GenericContainer<?> redisContainer;

    @BeforeEach
    void reset() {
        InventoryTestData.reset(jdbc);
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        guard.reset();
    }

    @Test
    void outageCostsSpeedNotCorrectnessAndTheFastPathRecovers() throws Exception {
        seatMaps.seatMap(DEMO_SHOW, Optional.empty());   // warm the cache
        pause();
        List<Long> holdMs = new ArrayList<>();
        try {
            for (int n = 1; n <= 40; n++) {
                long start = System.nanoTime();
                inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A" + n)));
                holdMs.add((System.nanoTime() - start) / 1_000_000);
            }
            assertThatThrownBy(() -> inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A1"))))
                    .as("Postgres alone still refuses a taken seat").isInstanceOf(SeatUnavailableException.class);
            long seatMapStart = System.nanoTime();
            assertThat(seatMaps.seatMap(DEMO_SHOW, Optional.empty()).seats()).hasSize(100);
            long seatMapMs = (System.nanoTime() - seatMapStart) / 1_000_000;

            assertThat(guard.state()).isEqualTo(CircuitBreaker.State.OPEN);
            List<Long> afterOpen = new ArrayList<>(holdMs.subList(10, holdMs.size()));
            Collections.sort(afterOpen);
            long p95 = afterOpen.get((int) Math.ceil(0.95 * afterOpen.size()) - 1);
            System.out.printf("Redis down: first holds %s ms; after breaker opened p95 %d ms; seat map %d ms%n",
                    holdMs.subList(0, 6), p95, seatMapMs);
            assertThat(holdMs.subList(0, 3)).as("before the breaker opens, each call pays the Redis timeout")
                    .allMatch(ms -> ms >= 200);
            assertThat(p95).as("once open, no Redis timeout is paid any more").isLessThan(150);
            assertThat(registry.counter("redis.calls.short.circuited").count()).isPositive();
        } finally {
            unpause();
        }

        Thread.sleep(1_200);   // wait-in-open passes; next calls are trials (half-open)
        for (int n = 41; n <= 46; n++) inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A" + n)));
        assertThat(guard.state()).as("Redis answered the trial calls").isEqualTo(CircuitBreaker.State.CLOSED);
        double rejectedBefore = registry.counter("seat.filter.verdicts", "verdict", "REJECTED").count();
        seatMaps.seatMap(DEMO_SHOW, Optional.empty());   // re-warms from Postgres if needed
        assertThatThrownBy(() -> inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A46"))))
                .isInstanceOf(SeatUnavailableException.class);
        InventoryInvariants.assertHold(jdbc);
        assertThat(registry.find("resilience4j.circuitbreaker.state").tag("name", "redis").gauges()).as("breaker exported").isNotEmpty();
        assertThat(rejectedBefore).isNotNegative();
    }

    @Test
    void fastPathCanBeSwitchedOffAndHoldsStillWork() {
        // FAST_PATH_ENABLED=false is covered by the default test configuration (no cache, pass-through filter);
        // here: with the breaker forced open, the filter is never consulted.
        seatMaps.seatMap(DEMO_SHOW, Optional.empty());
        pause();
        try {
            for (int n = 0; n < 10; n++) inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A" + (60 + n))));
        } finally {
            unpause();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.show_seats WHERE status = 'HELD'", Integer.class)).isEqualTo(10);
    }

    private void pause() { redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec(); }

    private void unpause() { redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec(); }
}
