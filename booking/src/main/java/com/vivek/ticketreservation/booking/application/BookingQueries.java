package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.BookingStore;
import com.vivek.ticketreservation.booking.application.port.Payments;
import com.vivek.ticketreservation.booking.application.port.Payments.PaymentInfo;
import com.vivek.ticketreservation.booking.domain.Booking;
import com.vivek.ticketreservation.booking.domain.BookingStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of the booking module. Never calls inventory: a booking carries copies of its seats and
 * prices (CQRS-lite, DESIGN_PATTERNS.md#21). The payment summary comes from the payment module in one
 * batched lookup per page.
 */
@Service
public class BookingQueries {
    public static final int MAX_PAGE_SIZE = 50;

    private final BookingStore bookings;
    private final Payments payments;

    public BookingQueries(BookingStore bookings, Payments payments) {
        this.bookings = bookings;
        this.payments = payments;
    }

    public BookingView get(UUID bookingId, UUID userId) {
        Booking booking = bookings.find(bookingId).filter(b -> b.belongsTo(userId))
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        return BookingView.of(booking, payments.find(bookingId).orElse(null));
    }

    /** A page of the user's bookings, newest first (P3.8). */
    public Page history(UUID userId, BookingStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("page >= 0 and 1 <= size <= " + MAX_PAGE_SIZE);
        List<Booking> found = bookings.findByUser(userId, status, page * size, size);
        Map<UUID, PaymentInfo> paid = payments.findAll(found.stream().map(Booking::id).toList());
        List<BookingView> items = found.stream().map(b -> BookingView.of(b, paid.get(b.id()))).toList();
        return new Page(items, page, size, bookings.countByUser(userId, status));
    }

    public record Page(List<BookingView> items, int page, int size, long totalItems) { }
}
