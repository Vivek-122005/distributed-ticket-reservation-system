package com.vivek.ticketreservation.common.web;

import java.time.Instant;

/**
 * Error body shared by every module's endpoints: {@code {code, message, retryable, timestamp}}
 * (schema ApiError in docs/api/*.yaml). Lives in {@code common} since P3.3 so inventory, booking and
 * the idempotency filter all produce the same shape.
 */
public record ApiError(String code, String message, boolean retryable, Instant timestamp) {
    public static ApiError of(String code, String message, boolean retryable) {
        return new ApiError(code, message, retryable, Instant.now());
    }
}
