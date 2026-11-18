package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.BookingStore;
import com.vivek.ticketreservation.booking.application.port.SeatReservations;
import com.vivek.ticketreservation.booking.domain.Booking;
import com.vivek.ticketreservation.booking.domain.BookingStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The compensation rule of P3.3 with in-memory fakes: if the booking can't be saved, the hold is released. */
class CreateBookingUseCaseTest {

    private final UUID holdId = UUID.randomUUID();
    private final List<UUID> released = new ArrayList<>();

    private final SeatReservations seats = new SeatReservations() {
        @Override public Reservation hold(UUID showId, UUID userId, List<String> codes) {
            return new Reservation(holdId, codes.stream().map(c -> new ReservedSeat(c, 1000)).toList(), "INR",
                    Instant.now().plusSeconds(300));
        }
        @Override public void confirm(UUID id, UUID userId) { }
        @Override public void release(UUID id, UUID userId) { released.add(id); }
    };

    private static BookingStore store(boolean failing) {
        return new BookingStore() {
            @Override public Booking insert(Booking b) {
                if (failing) throw new IllegalStateException("database down");
                return b;
            }
            @Override public Optional<Booking> find(UUID id) { return Optional.empty(); }
            @Override public boolean updateStatus(Booking b) { return true; }
            @Override public List<Booking> findByUser(UUID u, BookingStatus s, int offset, int limit) { return List.of(); }
            @Override public long countByUser(UUID u, BookingStatus s) { return 0; }
        };
    }

    @Test
    void savesAPendingBookingForTheHold() {
        BookingView view = new CreateBookingUseCase(seats, store(false))
                .execute(new CreateBookingUseCase.Command(UUID.randomUUID(), UUID.randomUUID(), List.of("A1", "A2")));
        assertThat(view.amountMinor()).isEqualTo(2000);
        assertThat(released).isEmpty();
    }

    @Test
    void releasesTheHoldWhenTheBookingCannotBeSaved() {
        CreateBookingUseCase useCase = new CreateBookingUseCase(seats, store(true));
        assertThatThrownBy(() -> useCase.execute(new CreateBookingUseCase.Command(UUID.randomUUID(), UUID.randomUUID(), List.of("A1"))))
                .hasMessage("database down");
        assertThat(released).containsExactly(holdId);
    }
}
