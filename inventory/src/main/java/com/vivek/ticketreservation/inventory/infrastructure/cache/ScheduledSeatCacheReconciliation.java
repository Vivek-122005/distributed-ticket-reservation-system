package com.vivek.ticketreservation.inventory.infrastructure.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;

/**
 * Runs {@link SeatCacheReconciler} for every cached show on sale, every {@code inventory.reconcile.interval}
 * (default 30 s). Read-only on Postgres and idempotent, so every replica may run it; at worst two replicas
 * repair the same entry with the same value. A Redis failure skips the round (the read path already falls
 * back to Postgres).
 */
class ScheduledSeatCacheReconciliation {
    private static final Logger log = LoggerFactory.getLogger(ScheduledSeatCacheReconciliation.class);
    private final SeatCacheReconciler reconciler;
    private final RedisGuard guard;

    ScheduledSeatCacheReconciliation(SeatCacheReconciler reconciler, RedisGuard guard) { this.reconciler = reconciler; this.guard = guard; }

    @Scheduled(fixedDelayString = "${inventory.reconcile.interval:30s}", initialDelayString = "${inventory.reconcile.interval:30s}")
    void tick() {
        if (guard.isOpen()) return;   // Redis is down; the next round after recovery repairs everything
        try {
            for (UUID show : reconciler.cachedShowsOnSale()) {
                SeatCacheReconciler.Report report = reconciler.reconcile(show);
                if (report.total() > 0) log.warn("Seat cache drift repaired for show {}: {}", show, report.repaired());
            }
        } catch (RuntimeException e) {
            log.debug("Seat cache reconciliation skipped: {}", e.getMessage());
        }
    }
}
