package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.BookingStore;
import com.vivek.ticketreservation.booking.application.port.Payments.PaymentInfo;
import com.vivek.ticketreservation.booking.application.port.SagaStore;
import com.vivek.ticketreservation.booking.application.port.SeatReservations;
import com.vivek.ticketreservation.booking.application.port.SeatReservations.Reservation;
import com.vivek.ticketreservation.booking.domain.Booking;
import com.vivek.ticketreservation.booking.domain.BookingSeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.UUID;

/**
 * The first saga steps (plan §4.5, P3.3 + P4.4): hold the seats, record a {@code PENDING_PAYMENT}
 * booking <b>and its saga row in one transaction</b>, then create the payment intent.
 *
 * <p>Hold and booking live in two modules, so two transactions: if the booking can't be saved the hold
 * is released at once (and expires anyway if even that fails). Booking and saga share a transaction,
 * so there is never a booking the recovery job doesn't know about. A failed intent is compensated by
 * the saga (seats released, booking FAILED). Retrying the request is safe: idempotency key (P3.4).
 */
@Service
public class CreateBookingUseCase {
    private static final Logger log = LoggerFactory.getLogger(CreateBookingUseCase.class);

    private final SeatReservations seats;
    private final BookingStore bookings;
    private final SagaStore sagas;
    private final BookingSaga saga;
    private final TransactionOperations tx;

    public CreateBookingUseCase(SeatReservations seats, BookingStore bookings, SagaStore sagas, BookingSaga saga,
                                TransactionOperations tx) {
        this.seats = seats;
        this.bookings = bookings;
        this.sagas = sagas;
        this.saga = saga;
        this.tx = tx;
    }

    public record Command(UUID userId, UUID showId, List<String> seatCodes) { }

    public BookingView execute(Command command) {
        Reservation hold = seats.hold(command.showId(), command.userId(), command.seatCodes());
        Booking pending = Booking.pending(UUID.randomUUID(), command.userId(), command.showId(), hold.holdId(),
                hold.seats().stream().map(s -> new BookingSeat(s.seatCode(), s.priceMinor())).toList(),
                hold.currency(), hold.expiresAt());
        Booking booking;
        try {
            booking = tx.execute(status -> {
                Booking saved = bookings.insert(pending);
                sagas.create(saved.id(), saga.firstCheck(saved.holdExpiresAt()));
                return saved;
            });
        } catch (RuntimeException e) {
            compensate(hold, command.userId(), e);
            throw e;
        }
        PaymentInfo payment = saga.start(booking);
        return BookingView.of(booking, payment);
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
