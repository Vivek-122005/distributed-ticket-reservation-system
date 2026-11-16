package com.vivek.ticketreservation.inventory;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldConflictException;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.support.Contention;
import com.vivek.ticketreservation.support.Contention.Outcome;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryInvariants;
import com.vivek.ticketreservation.support.InventoryTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code SeatLockingStrategy} contract (P1.6, run against every strategy in P1.7): the concurrency
 * guarantees under real contention on Postgres (FR-03, NFR-01, invariants 1-2).
 *
 * <p>LSP enforced by test: each strategy has a subclass that only selects it ({@code *LockingIT}), and
 * all of them must pass the same tests. A pool of 50 connections lets up to 50 transactions fight at the
 * database at once; thousands of attempts queue for them, as requests would in production. Every test
 * ends with the invariant check. Any exception other than the expected business outcome (for example a
 * deadlock) fails the test.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=50")
abstract class SeatLockingContract {

    @Autowired InventoryApi inventory;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        InventoryTestData.reset(jdbc);
        resetStrategyState();
    }

    /** Clears any state a strategy keeps outside Postgres (Redis marks and locks). */
    protected void resetStrategyState() { }

    /** Tells the strategy that a hold was expired by editing the database directly (test-only time travel). */
    protected void afterManualExpiry(HoldResult hold) { }

    @Test
    void fr03_500AttemptsForOneSeatHaveExactlyOneWinner() throws Exception {
        List<Outcome<HoldResult>> outcomes = Contention.race(500, i -> hold("A1"));

        assertOnlyExpectedFailures(outcomes, SeatUnavailableException.class);
        assertThat(successes(outcomes)).hasSize(1);
        assertThat(count("SELECT count(*) FROM inventory.holds")).as("losers' hold rows are rolled back").isEqualTo(1);
        InventoryInvariants.assertHold(jdbc);
    }

    @Test
    void nfr01_2000AttemptsFor100SeatsSellEachSeatExactlyOnce() throws Exception {
        List<Outcome<HoldResult>> outcomes = Contention.race(2_000, i -> hold("A" + (i % 100 + 1)));

        assertOnlyExpectedFailures(outcomes, SeatUnavailableException.class);
        List<HoldResult> winners = successes(outcomes);
        assertThat(winners).hasSize(100);
        Set<String> seatsWon = new HashSet<>();
        winners.forEach(w -> w.seats().forEach(s -> assertThat(seatsWon.add(s.seatCode())).as("seat won twice").isTrue()));
        assertThat(count("SELECT count(*) FROM inventory.show_seats WHERE status = 'HELD'")).isEqualTo(100);
        InventoryInvariants.assertHold(jdbc);
    }

    @Test
    void fr03_overlappingMultiSeatRequestsNeverHoldPartiallyNorDeadlock() throws Exception {
        List<String> pool = IntStream.rangeClosed(1, 12).mapToObj(n -> "A" + n).toList();
        Random random = new Random(42);   // fixed seed: reproducible request mix
        List<List<String>> requests = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            List<String> seats = new ArrayList<>(pool);
            Collections.shuffle(seats, random);   // random order per request: lock order is the database's job
            requests.add(List.copyOf(seats.subList(0, 2 + random.nextInt(3))));
        }

        List<Outcome<HoldResult>> outcomes = Contention.race(requests.size(), i -> hold(requests.get(i).toArray(String[]::new)));

        assertOnlyExpectedFailures(outcomes, SeatUnavailableException.class);
        List<HoldResult> winners = successes(outcomes);
        assertThat(winners).isNotEmpty();
        int seatsInWinningHolds = winners.stream().mapToInt(w -> w.seats().size()).sum();
        assertThat(count("SELECT count(*) FROM inventory.show_seats WHERE status = 'HELD'"))
                .as("every held seat belongs to a successful, complete hold").isEqualTo(seatsInWinningHolds);
        InventoryInvariants.assertHold(jdbc);
    }

    @Test
    void reverseOrderPairsDoNotDeadlock() throws Exception {
        // The classic deadlock: T1 locks A1 then wants A2, T2 locks A2 then wants A1.
        List<Outcome<HoldResult>> outcomes = Contention.race(400, i -> i % 2 == 0 ? hold("A1", "A2") : hold("A2", "A1"));

        assertOnlyExpectedFailures(outcomes, SeatUnavailableException.class);
        assertThat(successes(outcomes)).hasSize(1);
        InventoryInvariants.assertHold(jdbc);
    }

    @Test
    void fr04_expiredSeatStampedeHasExactlyOneWinner() throws Exception {
        HoldResult old = hold("A50");
        jdbc.update("UPDATE inventory.holds SET expires_at = now() - interval '1 second' WHERE id = ?", old.holdId());
        jdbc.update("UPDATE inventory.show_seats SET hold_expires_at = now() - interval '1 second' WHERE hold_id = ?", old.holdId());
        afterManualExpiry(old);

        List<Outcome<HoldResult>> outcomes = Contention.race(300, i -> hold("A50"));

        assertOnlyExpectedFailures(outcomes, SeatUnavailableException.class);
        assertThat(successes(outcomes)).hasSize(1);
        InventoryInvariants.assertHold(jdbc);
    }

    @Test
    void fr06_confirmRacingReleaseEndsInExactlyOneOutcome() throws Exception {
        List<HoldResult> holds = new ArrayList<>();
        List<UUID> owners = new ArrayList<>();
        for (int n = 60; n < 100; n++) {
            UUID owner = UUID.randomUUID();
            owners.add(owner);
            holds.add(inventory.hold(new HoldCommand(DEMO_SHOW, owner, List.of("A" + n))));
        }

        List<Outcome<HoldResult>> outcomes = Contention.race(holds.size() * 2, i -> {
            HoldResult hold = holds.get(i / 2);
            UUID owner = owners.get(i / 2);
            return i % 2 == 0 ? inventory.confirm(hold.holdId(), owner) : inventory.release(hold.holdId(), owner);
        });

        assertOnlyExpectedFailures(outcomes, HoldConflictException.class);
        for (int h = 0; h < holds.size(); h++) {
            Outcome<HoldResult> confirm = outcomes.get(2 * h);
            Outcome<HoldResult> release = outcomes.get(2 * h + 1);
            assertThat(confirm.succeeded() ^ release.succeeded()).as("exactly one of confirm/release wins for hold " + h).isTrue();
        }
        int confirmed = count("SELECT count(*) FROM inventory.holds WHERE status = 'CONFIRMED'");
        int released = count("SELECT count(*) FROM inventory.holds WHERE status = 'RELEASED'");
        assertThat(confirmed + released).isEqualTo(holds.size());
        assertThat(count("SELECT count(*) FROM inventory.show_seats WHERE status = 'BOOKED'")).isEqualTo(confirmed);
        InventoryInvariants.assertHold(jdbc);
    }

    private HoldResult hold(String... seats) {
        return inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of(seats)));
    }

    private static List<HoldResult> successes(List<Outcome<HoldResult>> outcomes) {
        return outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).toList();
    }

    /** Every failure must be the expected business outcome; anything else (deadlock, timeout) is a bug. */
    private static void assertOnlyExpectedFailures(List<? extends Outcome<?>> outcomes, Class<? extends Throwable> expected) {
        List<Throwable> unexpected = outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::error)
                .filter(e -> !expected.isInstance(e)).toList();
        assertThat(unexpected).as("unexpected failures, first: %s", unexpected.isEmpty() ? "-" : unexpected.get(0)).isEmpty();
    }

    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }
}
