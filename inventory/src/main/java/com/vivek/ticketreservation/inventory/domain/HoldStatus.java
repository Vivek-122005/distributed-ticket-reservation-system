package com.vivek.ticketreservation.inventory.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a hold: {@code HELD → CONFIRMED | RELEASED | EXPIRED}; the last three are terminal.
 * Stored and published with the same names (SOLID.md deviation #5, fixed in P1.0).
 *
 * <p>Pattern: State, enum form (docs/lld/DESIGN_PATTERNS.md#6).
 */
public enum HoldStatus {
    HELD, CONFIRMED, RELEASED, EXPIRED;

    public boolean canTransitionTo(HoldStatus next) { return allowedNext().contains(next); }

    public boolean isTerminal() { return allowedNext().isEmpty(); }

    private Set<HoldStatus> allowedNext() {
        return this == HELD ? EnumSet.of(CONFIRMED, RELEASED, EXPIRED) : EnumSet.noneOf(HoldStatus.class);
    }
}
