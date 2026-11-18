package com.vivek.ticketreservation.common.idempotency;

import java.time.Duration;
import java.util.UUID;

/**
 * Remembers, per user and {@code Idempotency-Key}, whether a request was already processed and what
 * it answered (P3.4). Port; {@link JdbcIdempotencyStore} is the Postgres adapter.
 *
 * <p>Pattern: Idempotency Key, see docs/lld/DESIGN_PATTERNS.md#12-idempotent-receiver--idempotency-key.
 */
public interface IdempotencyStore {

    /**
     * Atomically claims the key for this request, or reports what already happened to it.
     *
     * @param requestHash fingerprint of method, path and body; the same key with a different hash is a client bug
     * @param ttl         how long a completed answer is kept for replay
     * @param lease       after this long without completion an in-progress claim is presumed dead (crashed
     *                    request) and the same request may take it over
     */
    Outcome begin(UUID userId, String key, String requestHash, Duration ttl, Duration lease);

    /** Stores the answer; later retries with the same key get exactly this response. */
    void complete(UUID userId, String key, StoredResponse response);

    /** Forgets an in-progress claim (the request failed with a retryable error), so a retry runs again. */
    void abandon(UUID userId, String key);

    /** Deletes expired keys. @return how many */
    int purgeExpired(int limit);

    /** A response as stored for replay. */
    record StoredResponse(int status, String contentType, String location, byte[] body) { }

    /** Result of {@link #begin}. */
    sealed interface Outcome {
        /** This request owns the key: process it, then complete or abandon. */
        record Started() implements Outcome { }
        /** Already answered: replay the stored response. */
        record Completed(StoredResponse response) implements Outcome { }
        /** The first request with this key is still running. */
        record InProgress() implements Outcome { }
        /** The key was used with a different request. */
        record Mismatch() implements Outcome { }
    }
}
