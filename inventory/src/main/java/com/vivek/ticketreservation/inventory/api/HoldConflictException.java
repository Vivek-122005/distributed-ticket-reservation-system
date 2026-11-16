package com.vivek.ticketreservation.inventory.api;

/** The hold is in a state that does not allow the requested operation; nothing was changed. */
public class HoldConflictException extends RuntimeException {

    /** Stable error codes (schema ApiError.code). */
    public enum Reason {
        /** Confirm after expiry: the caller must start the refund path (FR-07). */
        HOLD_EXPIRED,
        /** Release of a hold that was already confirmed. */
        HOLD_ALREADY_CONFIRMED,
        /** Confirm of a hold that was released. */
        ILLEGAL_STATE_TRANSITION
    }

    private final Reason reason;

    public HoldConflictException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
