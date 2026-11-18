package com.vivek.ticketreservation.booking.infrastructure;

import com.vivek.ticketreservation.booking.application.BookingSaga;
import com.vivek.ticketreservation.booking.application.port.SagaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Drives every saga that is due (P4.4–P4.6): payment re-checks, hold-expiry timeouts, retries of failed
 * steps, and steps interrupted by a crash. Every replica runs it; {@code FOR UPDATE SKIP LOCKED} plus a
 * lease on {@code next_retry_at} give each due saga to one replica (competing consumers, as in P2.2).
 */
@Component
@ConditionalOnProperty(name = "booking.saga.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class SagaRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryJob.class);
    private static final Duration LEASE = Duration.ofSeconds(30);

    private final SagaStore sagas;
    private final BookingSaga saga;

    SagaRecoveryJob(SagaStore sagas, BookingSaga saga) {
        this.sagas = sagas;
        this.saga = saga;
    }

    @Scheduled(fixedDelayString = "${booking.saga.recovery.interval:2s}", initialDelayString = "${booking.saga.recovery.initial-delay:5s}")
    void scheduled() { runOnce(100); }

    /** @return how many due sagas were resumed */
    public int runOnce(int limit) {
        var due = sagas.claimDue(limit, LEASE);
        for (var s : due) saga.resume(s.bookingId(), s.step());
        if (!due.isEmpty()) log.debug("Resumed {} due sagas", due.size());
        return due.size();
    }
}
