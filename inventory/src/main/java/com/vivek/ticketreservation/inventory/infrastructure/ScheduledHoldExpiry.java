package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.application.ExpireHoldsJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Drives {@link ExpireHoldsJob} on a fixed delay ({@code inventory.expiry.interval}, default 5 s).
 * Each tick drains up to {@code max-batches} batches of {@code batch-size} holds, each batch its own
 * short transaction, so a backlog after an outage is worked off without one huge transaction.
 * Disabled with {@code inventory.expiry.enabled=false} (integration tests drive the job directly).
 *
 * <p>Poison-row isolation: if a batch fails (for example a row the database refuses to update), the
 * same holds are retried one by one, each in its own transaction. Good holds still expire, the bad one
 * is logged and counted ({@code hold_expiry_failures_total}), and the worker is never stuck behind it.
 * Found by the P2.1 smoke test: legacy rows from before V4 blocked every batch until V6 repaired them.
 */
@Component
@ConditionalOnProperty(name = "inventory.expiry.enabled", havingValue = "true", matchIfMissing = true)
class ScheduledHoldExpiry {
    private static final Logger log = LoggerFactory.getLogger(ScheduledHoldExpiry.class);
    private final ExpireHoldsJob job;
    private final int batchSize;
    private final int maxBatches;
    private final Counter failures;

    ScheduledHoldExpiry(ExpireHoldsJob job, MeterRegistry registry,
                        @Value("${inventory.expiry.batch-size:500}") int batchSize,
                        @Value("${inventory.expiry.max-batches:20}") int maxBatches) {
        this.job = job; this.batchSize = batchSize; this.maxBatches = maxBatches;
        this.failures = Counter.builder("hold.expiry.failures").description("Holds the expiry worker could not expire")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${inventory.expiry.interval:5s}", initialDelayString = "${inventory.expiry.interval:5s}")
    void tick() {
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int expired;
            try {
                expired = job.runBatch(batchSize);
            } catch (RuntimeException batchFailed) {
                log.warn("Expiry batch failed, retrying its holds one by one: {}", batchFailed.getMessage());
                total += oneByOne();
                break;
            }
            total += expired;
            if (expired < batchSize) break;
        }
        if (total > 0) log.info("Expired {} holds", total);
    }

    /** Package-private for tests: expire each due hold in its own transaction, skipping failures. */
    int oneByOne() {
        int expired = 0;
        for (UUID holdId : job.dueHoldIds(batchSize)) {
            try {
                if (job.runOne(holdId)) expired++;
            } catch (RuntimeException poison) {
                failures.increment();
                log.error("Hold {} could not be expired and is skipped: {}", holdId, poison.getMessage());
            }
        }
        return expired;
    }
}
