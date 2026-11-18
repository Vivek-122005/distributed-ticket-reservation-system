package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.BookingStore;
import com.vivek.ticketreservation.booking.application.port.SeatReservationException;
import com.vivek.ticketreservation.booking.application.port.SeatReservations;
import com.vivek.ticketreservation.booking.domain.Booking;
import com.vivek.ticketreservation.booking.domain.BookingStatus;
import com.vivek.ticketreservation.booking.domain.IllegalStateTransitionException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The user cancels a booking that is still {@code PENDING_PAYMENT}; its seats go back on sale (P3.7).
 * Idempotent: cancelling a cancelled booking returns it unchanged.
 *
 * <p>Order matters. The seats are released <b>first</b>, because inventory decides whether that is still
 * possible: if payment won the race and the hold is already confirmed, the release is refused and the
 * booking is not cancelled. Only after a successful release is the booking marked {@code CANCELLED},
 * with an optimistic-lock retry in case another step changed it meanwhile. A payment that is
 * captured after the cancel goes to the refund path (P4.5). Cancelling the payment intent is added
 * in P4.4.
 */
@Service
public class CancelBookingUseCase {
    private static final int MAX_ATTEMPTS = 3;

    private final BookingStore bookings;
    private final SeatReservations seats;

    public CancelBookingUseCase(BookingStore bookings, SeatReservations seats) {
        this.bookings = bookings;
        this.seats = seats;
    }

    public BookingView execute(UUID bookingId, UUID userId) {
        Booking booking = load(bookingId, userId);
        if (booking.status() == BookingStatus.CANCELLED) return BookingView.of(booking);
        if (booking.status() != BookingStatus.PENDING_PAYMENT) {
            throw new IllegalStateTransitionException(bookingId, booking.status(), BookingStatus.CANCELLED);
        }

        try {
            seats.release(booking.holdId(), userId);
        } catch (SeatReservationException e) {
            if (e.reason() != SeatReservationException.Reason.HOLD_NOT_FOUND) {
                // HOLD_ALREADY_CONFIRMED: payment got there first; the booking is being confirmed.
                throw new IllegalStateTransitionException(bookingId, booking.status(), BookingStatus.CANCELLED);
            }
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (booking.status() == BookingStatus.CANCELLED) return BookingView.of(booking);
            booking.cancel();   // throws if another step moved it to a non-cancellable state
            if (bookings.updateStatus(booking)) return BookingView.of(load(bookingId, userId));
            booking = load(bookingId, userId);
        }
        throw new IllegalStateException("Booking " + bookingId + " kept changing while being cancelled");
    }

    private Booking load(UUID bookingId, UUID userId) {
        return bookings.find(bookingId).filter(b -> b.belongsTo(userId))
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }
}
