package com.vivek.ticketreservation.booking.domain;

/** A booking state change that the state machine (plan §4.4) does not allow; nothing was changed. */
public class IllegalStateTransitionException extends RuntimeException {
    private final BookingStatus from;
    private final BookingStatus to;

    public IllegalStateTransitionException(Object bookingId, BookingStatus from, BookingStatus to) {
        super("Booking " + bookingId + " cannot go from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public BookingStatus from() { return from; }
    public BookingStatus to() { return to; }
}
