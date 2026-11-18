package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.BookingStore;
import com.vivek.ticketreservation.booking.application.port.SeatReservations;
import com.vivek.ticketreservation.booking.application.port.SeatReservations.Reservation;
import com.vivek.ticketreservation.booking.domain.Booking;
import com.vivek.ticketreservation.booking.domain.BookingSeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Hold the seats, then record a {@code PENDING_PAYMENT} booking for that hold (plan §4.5, P3.3).
 *
 * <p>Two steps in two modules, so two transactions: inventory commits the hold, then booking commits
 * the booking. If the second step fails, the hold is released at once (compensation) instead of
 * blocking the seats until it expires; if even the release fails, the hold's 5-minute expiry is the
 * backstop. Retrying the whole request is safe because of the idempotency key (P3.4). Payment intent
 * creation is added as the next saga step in P4.4.
 */
@Service
public class CreateBookingUseCase {
    private static final Logger log = LoggerFactory.getLogger(CreateBookingUseCase.class);

    private final SeatReservations seats;
    private final BookingStore bookings;

    public CreateBookingUseCase(SeatReservations seats, BookingStore bookings) {
        this.seats = seats;
        this.bookings = bookings;
    }

    public record Command(UUID userId, UUID showId, List<String> seatCodes) { }

    public BookingView execute(Command command) {
        Reservation hold = seats.hold(command.showId(), command.userId(), command.seatCodes());
        Booking booking = Booking.pending(UUID.randomUUID(), command.userId(), command.showId(), hold.holdId(),
                hold.seats().stream().map(s -> new BookingSeat(s.seatCode(), s.priceMinor())).toList(),
                hold.currency(), hold.expiresAt());
        try {
            return BookingView.of(bookings.insert(booking));
        } catch (RuntimeException e) {
            compensate(hold, command.userId(), e);
            throw e;
        }
    }

    private void compensate(Reservation hold, UUID userId, RuntimeException cause) {
        try {
            seats.release(hold.holdId(), userId);
        } catch (RuntimeException releaseFailure) {
            cause.addSuppressed(releaseFailure);
            log.warn("Booking insert failed and hold {} could not be released; it will expire", hold.holdId());
        }
    }
}
