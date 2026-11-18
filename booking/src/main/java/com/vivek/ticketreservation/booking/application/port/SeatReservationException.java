package com.vivek.ticketreservation.booking.application.port;

/** Inventory refused a reservation step; nothing was changed by that step. */
public class SeatReservationException extends RuntimeException {

    /** Stable codes, the same strings the API reports (schema ApiError.code). */
    public enum Reason { SEAT_UNAVAILABLE, SHOW_NOT_FOUND, SHOW_NOT_ON_SALE, HOLD_EXPIRED, HOLD_NOT_FOUND, ILLEGAL_STATE_TRANSITION }

    private final Reason reason;

    public SeatReservationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
