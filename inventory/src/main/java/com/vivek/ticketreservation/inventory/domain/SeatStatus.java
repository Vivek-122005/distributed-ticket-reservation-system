package com.vivek.ticketreservation.inventory.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of one show seat (plan §4.3).
 *
 * <p>Pattern: State, enum form (docs/lld/DESIGN_PATTERNS.md#6). {@code HELD → HELD} is the lazy
 * re-hold of a seat whose hold expired (P1.2). {@code BOOKED} is terminal.
 */
public enum SeatStatus {
    AVAILABLE, HELD, BOOKED;

    public boolean canTransitionTo(SeatStatus next) { return allowedNext().contains(next); }

    private Set<SeatStatus> allowedNext() {
        return switch (this) {
            case AVAILABLE -> EnumSet.of(HELD);
            case HELD -> EnumSet.of(HELD, BOOKED, AVAILABLE);
            case BOOKED -> EnumSet.noneOf(SeatStatus.class);
        };
    }
}
