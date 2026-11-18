package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.BookingStore;
import com.vivek.ticketreservation.booking.domain.BookingStatus;
import org.springframework.stereotype.Service;

import java.util.List;
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

    /** A page of the user's bookings, newest first (P3.8). */
    public Page history(UUID userId, BookingStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("page >= 0 and 1 <= size <= " + MAX_PAGE_SIZE);
        List<BookingView> items = bookings.findByUser(userId, status, page * size, size).stream().map(BookingView::of).toList();
        return new Page(items, page, size, bookings.countByUser(userId, status));
    }

    public static final int MAX_PAGE_SIZE = 50;

    public record Page(List<BookingView> items, int page, int size, long totalItems) { }
}
