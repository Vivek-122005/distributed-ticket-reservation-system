package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.support.Contention;
import com.vivek.ticketreservation.support.Contention.Outcome;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryInvariants;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P2.4: the fast-reject filter in front of Postgres ("cache as filter, database as authority"). */
@IntegrationTest
@Import(RedisContainerConfig.class)
@TestPropertySource(properties = {"inventory.seat-cache.enabled=true", "spring.datasource.hikari.maximum-pool-size=50"})
class SeatFilterIT {

    @Autowired InventoryApi inventory;
    @Autowired SeatMapQuery seatMaps;
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
    void takenSeatIsRejectedWithoutTouchingPostgres() throws Exception {
        warm();
        hold("A1");
        settle();
        int holdsBefore = count("SELECT count(*) FROM inventory.holds");
        double rejectedBefore = verdict("REJECTED");

        assertThatThrownBy(() -> hold("A1", "A2")).isInstanceOf(SeatUnavailableException.class);

        assertThat(verdict("REJECTED")).isEqualTo(rejectedBefore + 1);
        assertThat(count("SELECT count(*) FROM inventory.holds")).as("no transaction was even started").isEqualTo(holdsBefore);
        assertThat(cached("A2")).as("nothing marked for a rejected request").matches("\\d+\\|A\\|0");
    }

    @Test
    void stampedeOnOneSeatMostlyStopsInRedis() throws Exception {
        warm();
        double rejectedBefore = verdict("REJECTED");

        List<Outcome<HoldResult>> outcomes = Contention.race(1_000, i -> hold("A5"));

        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).allMatch(o -> o.error() instanceof SeatUnavailableException)).isTrue();
        assertThat(verdict("REJECTED") - rejectedBefore).as("the pending mark turns away the crowd before Postgres").isGreaterThan(900);
        InventoryInvariants.assertHold(jdbc);
    }

    @Test
    void whenPostgresSaysNoThePendingMarksAreUndone() throws Exception {
        warm();
        // Postgres knows A10 is booked; the cache was not told (as if an event was lost).
        UUID holdId = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory.holds (id, show_id, user_id, status, expires_at, seat_codes) VALUES (?, ?, ?, 'CONFIRMED', now(), ARRAY['A10'])",
                holdId, DEMO_SHOW, UUID.randomUUID());
        jdbc.update("UPDATE inventory.show_seats SET status = 'BOOKED', hold_id = ?, held_by_user_id = ?, hold_expires_at = now() "
                + "WHERE show_id = ? AND seat_code = 'A10'", holdId, UUID.randomUUID(), DEMO_SHOW);

        assertThatThrownBy(() -> hold("A10", "A11")).isInstanceOf(SeatUnavailableException.class);

        assertThat(cached("A11")).as("our pending mark is gone, A11 is free again").matches("\\d+\\|A\\|0");
        assertThat(hold("A11")).isNotNull();
    }

    @Test
    void successfulHoldReplacesThePendingMarkWithTheRealState() throws Exception {
        warm();
        hold("A20");
        settle();
        assertThat(cached("A20")).as("committed SeatsChanged (higher version) replaced the pending mark")
                .startsWith(jdbc.queryForObject("SELECT version FROM inventory.show_seats WHERE show_id = ? AND seat_code = 'A20'", Long.class, DEMO_SHOW) + "|H|");
    }

    @Test
    void coldCacheOrRedisDownMeansPostgresDecidesAlone() {
        assertThat(hold("A30")).as("not warmed: filter skips").isNotNull();

        warm();
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            assertThat(hold("A31")).as("Redis down: fail open to Postgres").isNotNull();
            assertThatThrownBy(() -> hold("A31")).as("and Postgres still refuses a taken seat").isInstanceOf(SeatUnavailableException.class);
        } finally {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
        assertThat(registry.counter("seat.filter.errors").count()).isPositive();
    }

    private void warm() { seatMaps.seatMap(DEMO_SHOW, Optional.empty()); }

    private HoldResult hold(String... seats) {
        return inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of(seats)));
    }

    private void settle() throws InterruptedException { assertThat(updater.drain(5_000)).isTrue(); }

    private String cached(String seat) { return (String) redis.opsForHash().get(SeatStateCache.key(DEMO_SHOW), seat); }

    private double verdict(String v) { return registry.counter("seat.filter.verdicts", "verdict", v).count(); }

    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }
}
