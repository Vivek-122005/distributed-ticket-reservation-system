package com.vivek.ticketreservation.inventory.infrastructure.locking;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.inventory.application.CreateHoldUseCase;
import com.vivek.ticketreservation.inventory.application.port.HoldStore;
import com.vivek.ticketreservation.inventory.application.port.SeatLockingStrategy;
import com.vivek.ticketreservation.inventory.application.port.ShowStore;
import com.vivek.ticketreservation.inventory.infrastructure.locking.LockingProperties.Strategy;
import com.vivek.ticketreservation.support.Contention;
import com.vivek.ticketreservation.support.Contention.Outcome;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryInvariants;
import com.vivek.ticketreservation.support.RedisContainerConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * P1.8: the same workloads against every {@link SeatLockingStrategy}, measured in one JVM against one
 * Postgres and one Redis. Not part of CI (it takes minutes); run with
 * {@code mvn -pl ticketing-app -am verify -Dbenchmark=true -Dit.test=LockingBenchmarkIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false}.
 * Results go to {@code ticketing-app/target/benchmark/locking-results.md}; the analysis is in
 * docs/results/locking-comparison.md and ADR 003.
 *
 * <p>Lives in the strategies' package so it can build each one directly and swap it under the real
 * {@link CreateHoldUseCase} without restarting Spring. Every run ends with the invariant check, so a
 * fast but wrong strategy can't win the table.
 */
@IntegrationTest
@Import(RedisContainerConfig.class)
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=50", "spring.data.redis.timeout=500ms"})
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockingBenchmarkIT {

    static final UUID BENCH_SHOW = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    static final int SEATS = 5_000;
    static final int RUNS = 3;

    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired StringRedisTemplate redis;
    @Autowired ShowStore shows;
    @Autowired HoldStore holds;
    @Autowired TransactionTemplate tx;
    @Autowired com.vivek.ticketreservation.inventory.application.SeatEvents seatEvents;
    @Autowired @Qualifier("redis") GenericContainer<?> redisContainer;

    private final List<String> report = new ArrayList<>();

    record Scenario(String name, String description, int attempts, IntFunction<List<String>> seatsFor) { }

    record Result(Strategy strategy, Scenario scenario, double throughput, double p50, double p95, double p99,
                  long winners, long rejected, long deadlocks, long otherErrors, String firstError) { }

    @BeforeAll
    void createBenchmarkShow() {
        jdbc.update("""
                INSERT INTO inventory.venues (id, name, city) VALUES ('00000000-0000-0000-0000-00000000b010', 'Bench Hall', 'Bengaluru')
                ON CONFLICT DO NOTHING""");
        jdbc.update("""
                INSERT INTO inventory.venue_sections (id, venue_id, code, default_price_minor)
                VALUES ('00000000-0000-0000-0000-00000000b011', '00000000-0000-0000-0000-00000000b010', 'B', 100000)
                ON CONFLICT DO NOTHING""");
        jdbc.update("""
                INSERT INTO inventory.seats (id, section_id, seat_code, row_no, col_no)
                SELECT gen_random_uuid(), '00000000-0000-0000-0000-00000000b011', 'B' || n, (n - 1) / 100 + 1, (n - 1) % 100 + 1
                  FROM generate_series(1, ?) AS n
                ON CONFLICT DO NOTHING""", SEATS);
        jdbc.update("""
                INSERT INTO inventory.shows (id, event_id, venue_id, starts_at, sale_opens_at, status)
                VALUES (?, '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000b010',
                        '2027-02-01T18:00:00Z', '2026-11-01T00:00:00Z', 'ON_SALE')
                ON CONFLICT DO NOTHING""", BENCH_SHOW);
        jdbc.update("""
                INSERT INTO inventory.show_seats (id, show_id, seat_id, seat_code, section_code, status, price_minor)
                SELECT gen_random_uuid(), ?, s.id, s.seat_code, 'B', 'AVAILABLE', 100000
                  FROM inventory.seats s WHERE s.section_id = '00000000-0000-0000-0000-00000000b011'
                ON CONFLICT DO NOTHING""", BENCH_SHOW);
    }

    static List<Scenario> scenarios() {
        Random random = new Random(7);
        List<List<String>> overlapping = IntStream.range(0, 2_000).mapToObj(i -> {
            List<Integer> pool = new ArrayList<>(IntStream.rangeClosed(1, 50).boxed().toList());
            Collections.shuffle(pool, random);
            return pool.subList(0, 2 + random.nextInt(3)).stream().map(n -> "B" + n).toList();
        }).toList();
        return List.of(
                new Scenario("hot-seat", "1,000 attempts on 1 seat", 1_000, i -> List.of("B1")),
                new Scenario("flash-sale", "5,000 single-seat attempts on 500 seats (10 per seat)", 5_000, i -> List.of("B" + (i % 500 + 1))),
                new Scenario("multi-seat", "2,000 requests of 2-4 seats over 50 seats, random order", 2_000, overlapping::get),
                new Scenario("uncontended", "2,000 attempts on 2,000 different seats", 2_000, i -> List.of("B" + (i + 1))));
    }

    @Test
    void compareStrategies() throws Exception {
        report.add("| Strategy | Scenario | Throughput (attempts/s) | p50 ms | p95 ms | p99 ms | Winners | Rejected | Deadlocks | Other errors |");
        report.add("|---|---|---|---|---|---|---|---|---|---|");
        for (Scenario scenario : scenarios()) {
            for (Strategy strategy : Strategy.values()) {
                Result result = measure(strategy, scenario);
                report.add(String.format(Locale.ROOT, "| %s | %s | %.0f | %.1f | %.1f | %.1f | %d | %d | %d | %d%s |",
                        strategy, scenario.name(), result.throughput(), result.p50(), result.p95(), result.p99(), result.winners(),
                        result.rejected(), result.deadlocks(), result.otherErrors(),
                        result.firstError() == null ? "" : " (" + result.firstError() + ")"));
            }
        }
        report.add("");
        report.add("### Redis unavailable (container paused), flash-sale scenario, 1 run");
        report.add("| Strategy | Winners | Failed | Deadlocks | p99 ms | Invariants |");
        report.add("|---|---|---|---|---|---|");
        for (Strategy strategy : Strategy.values()) redisDown(strategy);
        write();
    }

    private Result measure(Strategy strategy, Scenario scenario) throws Exception {
        runOnce(strategy, scenario);   // warm-up: JIT, connection pool, Postgres caches
        List<Double> throughputs = new ArrayList<>();
        List<Double> latencies = new ArrayList<>();
        long winners = 0, rejected = 0, deadlocks = 0, other = 0;
        String firstError = null;
        for (int run = 0; run < RUNS; run++) {
            Run r = runOnce(strategy, scenario);
            throughputs.add(r.throughput);
            latencies.addAll(r.latenciesMs);
            winners += r.winners; rejected += r.rejected; deadlocks += r.deadlocks; other += r.other;
            if (firstError == null) firstError = r.firstError;
        }
        Collections.sort(throughputs);
        Collections.sort(latencies);
        return new Result(strategy, scenario, throughputs.get(RUNS / 2), pct(latencies, 50), pct(latencies, 95), pct(latencies, 99),
                winners / RUNS, rejected / RUNS, deadlocks / RUNS, other / RUNS, firstError);
    }

    private static final class Run {
        double throughput; List<Double> latenciesMs = new ArrayList<>(); long winners, rejected, deadlocks, other; String firstError;
    }

    private Run runOnce(Strategy strategy, Scenario scenario) throws Exception {
        reset();
        CreateHoldUseCase useCase = new CreateHoldUseCase(shows, build(strategy), holds, seatEvents);
        long start = System.nanoTime();
        List<Outcome<Double>> outcomes = Contention.race(scenario.attempts(), i -> {
            long t0 = System.nanoTime();
            try {
                tx.executeWithoutResult(s -> useCase.execute(new HoldCommand(BENCH_SHOW, UUID.randomUUID(), scenario.seatsFor().apply(i))));
            } catch (SeatUnavailableException rejected) {
                throw new Rejected((System.nanoTime() - t0) / 1e6);
            }
            return (System.nanoTime() - t0) / 1e6;
        });
        double seconds = (System.nanoTime() - start) / 1e9;
        Run run = new Run();
        run.throughput = scenario.attempts() / seconds;
        for (Outcome<Double> o : outcomes) {
            if (o.succeeded()) { run.winners++; run.latenciesMs.add(o.value()); }
            else if (o.error() instanceof Rejected r) { run.rejected++; run.latenciesMs.add(r.ms); }
            else if (String.valueOf(o.error().getMessage()).contains("deadlock")) run.deadlocks++;
            else { run.other++; if (run.firstError == null) run.firstError = o.error().getClass().getSimpleName(); }
        }
        InventoryInvariants.assertHold(jdbc);
        return run;
    }

    private void redisDown(Strategy strategy) throws Exception {
        Scenario flash = scenarios().get(1);
        reset();
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            Run run = runOnceWithoutReset(strategy, flash);
            report.add(String.format(Locale.ROOT, "| %s | %d | %d | %d | %.0f | %s |", strategy, run.winners, run.other + run.rejected,
                    run.deadlocks, pct(new ArrayList<>(run.latenciesMs.stream().sorted().toList()), 99), "hold ✓"));
        } finally {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
    }

    private Run runOnceWithoutReset(Strategy strategy, Scenario scenario) throws Exception {
        CreateHoldUseCase useCase = new CreateHoldUseCase(shows, build(strategy), holds, seatEvents);
        List<Outcome<Double>> outcomes = Contention.race(scenario.attempts(), i -> {
            long t0 = System.nanoTime();
            try {
                tx.executeWithoutResult(s -> useCase.execute(new HoldCommand(BENCH_SHOW, UUID.randomUUID(), scenario.seatsFor().apply(i))));
            } catch (RuntimeException e) {
                throw new Rejected((System.nanoTime() - t0) / 1e6);
            }
            return (System.nanoTime() - t0) / 1e6;
        });
        Run run = new Run();
        for (Outcome<Double> o : outcomes) {
            if (o.succeeded()) { run.winners++; run.latenciesMs.add(o.value()); }
            else { run.other++; run.latenciesMs.add(((Rejected) o.error()).ms); }
        }
        InventoryInvariants.assertHold(jdbc);
        return run;
    }

    private SeatLockingStrategy build(Strategy strategy) {
        SeatRows rows = new SeatRows(named);
        return switch (strategy) {
            case CONDITIONAL_UPDATE -> new ConditionalUpdateStrategy(named);
            case PESSIMISTIC -> new PessimisticLockStrategy(rows);
            case OPTIMISTIC -> new OptimisticLockStrategy(rows);
            case REDIS_LOCK -> new RedisLockStrategy(rows, redis);
            case REDIS_LUA -> new RedisLuaStrategy(new ConditionalUpdateStrategy(named), redis);
        };
    }

    private void reset() {
        jdbc.update("""
                UPDATE inventory.show_seats SET status = 'AVAILABLE', hold_id = NULL, held_by_user_id = NULL,
                       hold_expires_at = NULL, lock_fence = 0 WHERE show_id = ?""", BENCH_SHOW);
        jdbc.update("DELETE FROM inventory.holds WHERE show_id = ?", BENCH_SHOW);
        var keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        jdbc.execute("VACUUM ANALYZE inventory.show_seats");
    }

    private static double pct(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private void write() throws Exception {
        Path out = Path.of("target", "benchmark", "locking-results.md");
        Files.createDirectories(out.getParent());
        List<String> lines = new ArrayList<>(List.of("# Locking benchmark (raw)", "",
                "Machine: " + System.getProperty("os.arch") + ", " + Runtime.getRuntime().availableProcessors() + " CPUs, Java "
                        + Runtime.version() + "; pool 50; median of " + RUNS + " runs after 1 warm-up; latency over all runs.", ""));
        scenarios().forEach(s -> lines.add("- **" + s.name() + "**: " + s.description()));
        lines.add("");
        lines.addAll(report);
        Files.write(out, lines);
        System.out.println(String.join("\n", lines));
    }

    /** Marks a business rejection (seat unavailable) while keeping its latency. */
    static final class Rejected extends RuntimeException {
        final double ms;
        Rejected(double ms) { super(null, null, false, false); this.ms = ms; }
    }
}
