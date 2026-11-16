package com.vivek.ticketreservation.inventory.infrastructure.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One circuit breaker in front of every Redis call of the inventory module (P2.6).
 *
 * <p>Pattern: Circuit Breaker (docs/lld/DESIGN_PATTERNS.md#18). Without it, a dead Redis costs every
 * request the full command timeout (250 ms) before the fallback runs; with it, after a few failures the
 * breaker opens and requests go straight to the fallback (Postgres, or "skip the filter") in
 * microseconds. After {@code wait-in-open} it lets a few trial calls through (half-open) and closes again
 * once Redis answers. Shared by the filter, cache reads, cache updates and reconciliation, so all of them
 * learn about an outage from whichever notices first.
 */
class RedisGuard {
    private final CircuitBreaker breaker;
    private final Counter shortCircuited;

    RedisGuard(CircuitBreaker breaker, MeterRegistry registry) {
        this.breaker = breaker;
        this.shortCircuited = Counter.builder("redis.calls.short.circuited")
                .description("Redis calls skipped because the breaker was open").register(registry);
    }

    /** Runs {@code call} if the breaker allows it; otherwise, or if it fails, returns {@code fallback}. */
    <T> T call(Supplier<T> call, Supplier<T> fallback) {
        if (!breaker.tryAcquirePermission()) {
            shortCircuited.increment();
            return fallback.get();
        }
        long start = System.nanoTime();
        T result;
        try {
            result = call.get();
        } catch (RuntimeException failure) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, failure);
            return fallback.get();
        }
        breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        return result;
    }

    void run(Runnable call, Runnable fallback) {
        call(() -> { call.run(); return null; }, () -> { fallback.run(); return null; });
    }

    boolean isOpen() { return breaker.getState() == CircuitBreaker.State.OPEN; }

    CircuitBreaker.State state() { return breaker.getState(); }

    /** Back to CLOSED with empty statistics (tests, or an operator after a known-good recovery). */
    void reset() { breaker.reset(); }
}
