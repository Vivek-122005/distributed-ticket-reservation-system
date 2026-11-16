package com.vivek.ticketreservation.inventory.domain;

import java.util.EnumSet;
import java.util.Set;

/** Sale lifecycle of a show. Pattern: State, enum form (docs/lld/DESIGN_PATTERNS.md#6). */
public enum ShowStatus {
    SCHEDULED, ON_SALE, SOLD_OUT, CLOSED;

    public boolean canTransitionTo(ShowStatus next) { return allowedNext().contains(next); }

    private Set<ShowStatus> allowedNext() {
        return switch (this) {
            case SCHEDULED -> EnumSet.of(ON_SALE, CLOSED);
            case ON_SALE -> EnumSet.of(SOLD_OUT, CLOSED);
            case SOLD_OUT -> EnumSet.of(ON_SALE, CLOSED);   // seats came back (release / expiry)
            case CLOSED -> EnumSet.noneOf(ShowStatus.class); // BR: a closed show is never reopened (UC-09 9a)
        };
    }
}
