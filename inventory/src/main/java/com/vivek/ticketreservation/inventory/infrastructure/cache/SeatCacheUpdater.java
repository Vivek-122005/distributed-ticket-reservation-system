package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.domain.event.SeatsChanged;
import com.vivek.ticketreservation.inventory.infrastructure.cache.SeatStateCache.CachedSeat;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Observer that copies committed {@link SeatsChanged} events into the Redis cache (P2.3).
 *
 * <p>Asynchronous on purpose. After-commit listeners still run on the request thread, before the
 * database connection is returned to the pool. A Redis call there would recreate the P1.8 failure
 * (Redis hangs → pooled connections stuck → whole app stuck, ADR 003). So the listener only enqueues,
 * on a bounded queue. If Redis is slow and the queue fills, updates are dropped and counted, never
 * blocking a request. A dropped or failed update leaves the cache stale, which the version check
 * tolerates and reconciliation (P2.5) repairs.
 */
class SeatCacheUpdater {
    private static final Logger log = LoggerFactory.getLogger(SeatCacheUpdater.class);
    private final SeatStateCache cache;
    private final ThreadPoolExecutor executor;
    private final Counter applied;
    private final Counter dropped;
    private final Counter failed;

    SeatCacheUpdater(SeatStateCache cache, MeterRegistry registry, int queueCapacity) {
        this.cache = cache;
        this.applied = counter(registry, "applied");
        this.dropped = counter(registry, "dropped");
        this.failed = counter(registry, "failed");
        this.executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("seat-cache-", 0).daemon(true).factory(), (task, pool) -> dropped.increment());
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder("seat.cache.updates").description("Seat cache updates by result").tag("result", result).register(registry);
    }

    @TransactionalEventListener
    void on(SeatsChanged event) {
        var seats = event.seats().stream()
                .map(s -> new CachedSeat(s.seatCode(), s.status(), s.holdExpiresAt(), s.version())).toList();
        executor.execute(() -> {
            try {
                cache.apply(event.showId(), seats);
                applied.increment();
            } catch (RuntimeException e) {
                failed.increment();
                log.debug("Seat cache update for show {} failed: {}", event.showId(), e.getMessage());
            }
        });
    }

    /** For tests and shutdown: waits until queued updates are applied. */
    boolean drain(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (executor.getActiveCount() > 0 || !executor.getQueue().isEmpty()) {
            if (System.currentTimeMillis() > deadline) return false;
            Thread.sleep(10);
        }
        return true;
    }

    void shutdown() { executor.shutdown(); }
}
