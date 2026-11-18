package com.vivek.ticketreservation.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a payment (plan §4.4b): {@code INITIATED → PROCESSING → CAPTURED | FAILED | UNKNOWN},
 * {@code UNKNOWN → CAPTURED | FAILED} (reconciliation), {@code CAPTURED → REFUNDED}. Added in P4.2:
 * {@code INITIATED → CANCELLED} when the booking is cancelled or expires before the user pays.
 *
 * <p>Pattern: State, enum form (docs/lld/DESIGN_PATTERNS.md#6). {@code UNKNOWN} is an honest state, not
 * a failure: the gateway did not answer and the money may or may not have moved.
 */
public enum PaymentStatus {
    INITIATED, PROCESSING, CAPTURED, FAILED, UNKNOWN, REFUNDED, CANCELLED;

    public boolean canTransitionTo(PaymentStatus next) { return allowedNext().contains(next); }

    public boolean isTerminal() { return allowedNext().isEmpty(); }

    private Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case INITIATED -> EnumSet.of(PROCESSING, CANCELLED);
            case PROCESSING -> EnumSet.of(CAPTURED, FAILED, UNKNOWN);
            case UNKNOWN -> EnumSet.of(CAPTURED, FAILED);
            case CAPTURED -> EnumSet.of(REFUNDED);
            case FAILED, REFUNDED, CANCELLED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }
}
