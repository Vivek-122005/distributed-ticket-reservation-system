package com.vivek.ticketreservation.booking.infrastructure;

import com.vivek.ticketreservation.common.idempotency.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired booking idempotency keys in small batches. Housekeeping only: an expired key is
 * already reclaimable by {@code begin} before it is purged, so correctness never depends on this.
 */
@Component
@EnableScheduling
class IdempotencyKeyPurge {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurge.class);
    private final IdempotencyStore store;

    IdempotencyKeyPurge(IdempotencyStore bookingIdempotencyStore) { this.store = bookingIdempotencyStore; }

    @Scheduled(fixedDelayString = "${booking.idempotency.purge-interval:10m}", initialDelayString = "1m")
    void purge() {
        int purged = store.purgeExpired(1000);
        if (purged > 0) log.info("Purged {} expired idempotency keys", purged);
    }
}
