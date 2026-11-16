package com.vivek.ticketreservation.inventory.application.port;

import java.time.Duration;

/**
 * Outbound port: business metrics of the hold path (P1.9). Keeps Micrometer out of the application
 * layer (DIP); the adapter in {@code infrastructure} publishes to Prometheus.
 */
public interface HoldMetrics {

    /** How a hold request ended. {@code SEAT_UNAVAILABLE} is a conflict: someone else got a seat first. */
    enum Outcome { HELD, SEAT_UNAVAILABLE, SHOW_NOT_ON_SALE, SHOW_NOT_FOUND, INVALID_REQUEST, ERROR }

    /** One hold request, with its end-to-end latency including commit. */
    void holdAttempt(Outcome outcome, Duration latency);

    /** One confirm or release call and how it ended (e.g. {@code CONFIRMED}, {@code HOLD_EXPIRED}). */
    void holdTransition(String operation, String outcome);
}
