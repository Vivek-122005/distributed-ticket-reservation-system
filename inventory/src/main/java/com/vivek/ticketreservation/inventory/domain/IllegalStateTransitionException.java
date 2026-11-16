package com.vivek.ticketreservation.inventory.domain;

/** A state change that the state machine (plan §4.3–4.4) does not allow. */
public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(String what, Enum<?> from, Enum<?> to) {
        super(what + " cannot go from " + from + " to " + to);
    }
}
