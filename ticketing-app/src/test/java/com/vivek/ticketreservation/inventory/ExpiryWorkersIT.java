package com.vivek.ticketreservation.inventory;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.inventory.application.ExpireHoldsJob;
import com.vivek.ticketreservation.inventory.domain.event.HoldExpired;
import com.vivek.ticketreservation.support.Contention;
import com.vivek.ticketreservation.support.Contention.Outcome;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryInvariants;
import com.vivek.ticketreservation.support.InventoryTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2.2: why no leader election is needed. Many expiry workers at once, as on many replicas, each hold
 * expired exactly once, no worker blocked, no event duplicated; and an expiry recorded by release counts
 * the same as one recorded by the worker.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=20")
@Import(ExpiryWorkersIT.Captured.class)
class ExpiryWorkersIT {

    static final int HOLDS = 2_000;

    @Autowired ExpireHoldsJob job;
    @Autowired InventoryApi inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired Captured captured;

    @BeforeEach
    void reset() {
        InventoryTestData.reset(jdbc);
        captured.events.clear();
    }

    @Test
    void eightConcurrentWorkersExpireEveryHoldExactlyOnce() throws Exception {
        seedExpiredHolds();

        long start = System.nanoTime();
        List<Outcome<Integer>> workers = Contention.race(8, w -> {
            int mine = 0, batch;
            while ((batch = job.runBatch(50)) > 0) mine += batch;   // like 8 replicas ticking at once
            return mine;
        });
        double seconds = (System.nanoTime() - start) / 1e9;

        assertThat(workers).allMatch(Outcome::succeeded);
        int total = workers.stream().mapToInt(Outcome::value).sum();
        assertThat(total).as("every hold expired once, none twice").isEqualTo(HOLDS);
        assertThat(workers.stream().filter(o -> o.value() > 0).count()).as("work was actually shared").isGreaterThan(1);
        assertThat(count("SELECT count(*) FROM inventory.holds WHERE status = 'EXPIRED'")).isEqualTo(HOLDS);
        assertThat(count("SELECT count(*) FROM inventory.show_seats WHERE status = 'AVAILABLE'")).isEqualTo(100);
        Set<UUID> announced = captured.events.stream().map(HoldExpired::holdId).collect(Collectors.toSet());
        assertThat(captured.events).hasSize(HOLDS);
        assertThat(announced).as("no duplicate events").hasSize(HOLDS);
        InventoryInvariants.assertHold(jdbc);
        System.out.printf("8 workers expired %d holds in %.2f s (%s per worker)%n", HOLDS, seconds,
                workers.stream().map(o -> String.valueOf(o.value())).collect(Collectors.joining(", ")));
    }

    @Test
    void releaseRacingTheWorkerOnAnExpiredHoldAnnouncesTheExpiryOnce() throws Exception {
        UUID user = UUID.randomUUID();
        HoldResult hold = inventory.hold(new HoldCommand(DEMO_SHOW, user, List.of("A1", "A2")));
        jdbc.update("UPDATE inventory.holds SET expires_at = now() - interval '1 second' WHERE id = ?", hold.holdId());
        jdbc.update("UPDATE inventory.show_seats SET hold_expires_at = now() - interval '1 second' WHERE hold_id = ?", hold.holdId());

        List<Outcome<Object>> outcomes = Contention.race(2, i -> i == 0 ? job.runBatch(10) : inventory.release(hold.holdId(), user));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(jdbc.queryForObject("SELECT status FROM inventory.holds WHERE id = ?", String.class, hold.holdId())).isEqualTo("EXPIRED");
        assertThat(captured.events).singleElement().satisfies(e -> assertThat(e.releasedSeatCodes()).containsExactly("A1", "A2"));
    }

    /** 100 expired holds owning one seat each, plus 1,900 expired holds whose seats were re-held long ago. */
    private void seedExpiredHolds() {
        jdbc.update("""
                INSERT INTO inventory.holds (id, show_id, user_id, status, expires_at, seat_codes)
                SELECT gen_random_uuid(), ?, gen_random_uuid(), 'HELD', now() - (n || ' seconds')::interval, ARRAY['A' || (n % 100 + 1)]
                  FROM generate_series(1, ?) AS n""", DEMO_SHOW, HOLDS);
        jdbc.update("""
                UPDATE inventory.show_seats s
                   SET status = 'HELD', hold_id = h.id, held_by_user_id = h.user_id, hold_expires_at = h.expires_at
                  FROM (SELECT DISTINCT ON (seat_codes[1]) id, user_id, expires_at, seat_codes[1] AS code
                          FROM inventory.holds ORDER BY seat_codes[1], expires_at DESC) h
                 WHERE s.show_id = ? AND s.seat_code = h.code""", DEMO_SHOW);
    }

    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }

    @TestConfiguration
    static class Captured {
        final List<HoldExpired> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void on(HoldExpired event) { events.add(event); }
    }
}
