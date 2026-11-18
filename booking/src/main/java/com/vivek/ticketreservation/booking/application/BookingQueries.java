package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.BookingStore;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Read side of the booking module. Never calls inventory: a booking carries copies of its seats and
 * prices, so history keeps working if inventory is slow or down (CQRS-lite, DESIGN_PATTERNS.md#21).
 */
@Service
public class BookingQueries {
    private final BookingStore bookings;

    public BookingQueries(BookingStore bookings) { this.bookings = bookings; }

    public BookingView get(UUID bookingId, UUID userId) {
        return bookings.find(bookingId).filter(b -> b.belongsTo(userId)).map(BookingView::of)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }
}
