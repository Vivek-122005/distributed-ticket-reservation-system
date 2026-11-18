package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.BookingStore;
import com.vivek.ticketreservation.booking.domain.Booking;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Load → domain transition → version-checked write, retried on conflict. The saga, the user's cancel and
 * the recovery job may all touch one booking at once; none of them overwrites another's change.
 */
@Component
public class BookingTransitions {
    private static final int MAX_ATTEMPTS = 5;
    private final BookingStore bookings;

    public BookingTransitions(BookingStore bookings) { this.bookings = bookings; }

    /** @param change a domain method (returns whether it changed anything); illegal transitions throw */
    public Booking apply(UUID bookingId, Predicate<Booking> change) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Booking booking = bookings.find(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
            if (!change.test(booking)) return booking;
            if (bookings.updateStatus(booking)) return bookings.find(bookingId).orElseThrow();
        }
        throw new IllegalStateException("Booking " + bookingId + " kept changing; giving up after " + MAX_ATTEMPTS + " attempts");
    }
}
