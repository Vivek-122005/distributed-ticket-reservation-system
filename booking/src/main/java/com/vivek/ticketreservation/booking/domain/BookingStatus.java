package com.vivek.ticketreservation.booking.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a booking (plan §4.4):
 * {@code PENDING_PAYMENT → CONFIRMED | FAILED | EXPIRED | CANCELLED}; a payment that arrives after
 * {@code EXPIRED} or {@code CANCELLED} moves it to {@code REFUND_PENDING → REFUNDED}.
 *
 * <p>Pattern: State, enum form (docs/lld/DESIGN_PATTERNS.md#6). The table is the single source of
 * which transitions are legal; {@link Booking} only asks it.
 */
public enum BookingStatus {
    PENDING_PAYMENT, CONFIRMED, FAILED, EXPIRED, CANCELLED, REFUND_PENDING, REFUNDED;

    public boolean canTransitionTo(BookingStatus next) { return allowedNext().contains(next); }

    public boolean isTerminal() { return allowedNext().isEmpty(); }

    private Set<BookingStatus> allowedNext() {
        return switch (this) {
            case PENDING_PAYMENT -> EnumSet.of(CONFIRMED, FAILED, EXPIRED, CANCELLED);
            case EXPIRED, CANCELLED -> EnumSet.of(REFUND_PENDING);
            case REFUND_PENDING -> EnumSet.of(REFUNDED);
            case CONFIRMED, FAILED, REFUNDED -> EnumSet.noneOf(BookingStatus.class);
        };
    }
}
