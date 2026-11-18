package com.vivek.ticketreservation.booking.application.port;

import com.vivek.ticketreservation.booking.domain.SagaStep;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of the booking saga's progress (P4.4). */
public interface SagaStore {

    record Saga(UUID bookingId, SagaStep step, int attempts, Instant nextRetryAt, String lastError) { }

    /** Starts the saga at AWAITING_PAYMENT; must run in the booking's insert transaction. */
    void create(UUID bookingId, Instant nextCheckAt);

    Optional<Saga> find(UUID bookingId);

    /** Records that the saga is now at {@code step} (resets attempts); {@code nextRetryAt} is when to look again if nothing happens. */
    void moveTo(UUID bookingId, SagaStep step, Instant nextRetryAt);

    /** The current step failed with a retryable error; try again at {@code nextRetryAt}. */
    void retryLater(UUID bookingId, String error, Instant nextRetryAt);

    /**
     * Claims up to {@code limit} unfinished sagas that are due, pushing their next check {@code lease}
     * into the future so other replicas skip them ({@code FOR UPDATE SKIP LOCKED}).
     */
    List<Saga> claimDue(int limit, Duration lease);
}
