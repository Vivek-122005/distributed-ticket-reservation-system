package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.application.port.HoldMetrics;
import com.vivek.ticketreservation.inventory.infrastructure.locking.LockingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@link HoldMetrics} on Micrometer, exposed at {@code /actuator/prometheus} as:
 * <ul>
 *   <li>{@code seat_hold_attempts_total{outcome, strategy}}: every hold request</li>
 *   <li>{@code seat_hold_conflicts_total{strategy}}: requests that lost a seat to someone else</li>
 *   <li>{@code hold_latency_seconds{outcome, strategy}}: histogram with SLO buckets, for p50/p95/p99
 *       (NFR-03: p95 &lt; 250 ms, p99 &lt; 500 ms)</li>
 *   <li>{@code hold_transitions_total{operation, outcome}}: confirm / release results</li>
 * </ul>
 * The {@code strategy} tag lets dashboards compare locking strategies under the same load (P1.8, P9).
 * Meters are created once and cached, so the hot path does no registry lookups.
 */
@Component
class MicrometerHoldMetrics implements HoldMetrics {
    private final MeterRegistry registry;
    private final Map<Outcome, Counter> attempts = new EnumMap<>(Outcome.class);
    private final Map<Outcome, Timer> latency = new EnumMap<>(Outcome.class);
    private final Counter conflicts;

    MicrometerHoldMetrics(MeterRegistry registry, LockingProperties locking) {
        this.registry = registry;
        String strategy = locking.strategy().name().toLowerCase(Locale.ROOT).replace('_', '-');
        for (Outcome outcome : Outcome.values()) {
            attempts.put(outcome, Counter.builder("seat.hold.attempts").description("Hold requests by outcome")
                    .tag("outcome", outcome.name()).tag("strategy", strategy).register(registry));
            latency.put(outcome, Timer.builder("hold.latency").description("Hold request latency including commit")
                    .tag("outcome", outcome.name()).tag("strategy", strategy)
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500))
                    .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(5))
                    .register(registry));
        }
        conflicts = Counter.builder("seat.hold.conflicts").description("Hold requests rejected because a seat was taken")
                .tag("strategy", strategy).register(registry);
    }

    @Override
    public void holdAttempt(Outcome outcome, Duration took) {
        attempts.get(outcome).increment();
        latency.get(outcome).record(took);
        if (outcome == Outcome.SEAT_UNAVAILABLE) conflicts.increment();
    }

    @Override
    public void holdTransition(String operation, String outcome) {
        registry.counter("hold.transitions", "operation", operation, "outcome", outcome).increment();
    }
}
