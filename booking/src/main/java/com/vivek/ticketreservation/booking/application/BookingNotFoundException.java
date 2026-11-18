package com.vivek.ticketreservation.booking.application;

import java.util.UUID;

/** No such booking for this user (other users' bookings are reported as not found, never as forbidden). */
public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(UUID bookingId) { super("Booking " + bookingId + " not found"); }
}
