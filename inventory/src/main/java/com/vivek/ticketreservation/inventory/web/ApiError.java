package com.vivek.ticketreservation.inventory.web;

import java.time.Instant;

/** Error body shared by all endpoints: {@code {code, message, retryable, timestamp}} (schema ApiError). */
public record ApiError(String code, String message, boolean retryable, Instant timestamp) {
    public static ApiError of(String code, String message, boolean retryable) {
        return new ApiError(code, message, retryable, Instant.now());
    }
}
