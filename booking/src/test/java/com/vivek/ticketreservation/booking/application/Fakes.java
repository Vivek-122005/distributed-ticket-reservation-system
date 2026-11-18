package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.BookingStore;
import com.vivek.ticketreservation.booking.application.port.Payments;
import com.vivek.ticketreservation.booking.application.port.SagaStore;
import com.vivek.ticketreservation.booking.application.port.SeatReservationException;
import com.vivek.ticketreservation.booking.application.port.SeatReservations;
import com.vivek.ticketreservation.booking.domain.Booking;
import com.vivek.ticketreservation.booking.domain.BookingStatus;
import com.vivek.ticketreservation.booking.domain.SagaStep;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory implementations of booking's ports, so the use cases and the saga run without Spring or a database. */
final class Fakes {
    final Map<UUID, Booking> bookings = new ConcurrentHashMap<>();
    final Map<UUID, SagaStore.Saga> sagas = new ConcurrentHashMap<>();
    final Map<UUID, Payments.Status> payments = new ConcurrentHashMap<>();
    final List<String> calls = new ArrayList<>();
    final Map<UUID, String> holds = new HashMap<>();   // holdId -> HELD / CONFIRMED / RELEASED / EXPIRED
    boolean failInsert;
    boolean failIntent;
    Instant now = Instant.parse("2026-11-18T10:00:00Z");

    Clock clock() {
        return new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        };
    }

    final SeatReservations seats = new SeatReservations() {
        @Override public Reservation hold(UUID showId, UUID userId, List<String> codes) {
            UUID id = UUID.randomUUID();
            holds.put(id, "HELD");
            return new Reservation(id, codes.stream().map(c -> new ReservedSeat(c, 1000)).toList(), "INR", now.plusSeconds(300));
        }
        @Override public void confirm(UUID holdId, UUID userId) {
            calls.add("confirm");
            String s = holds.get(holdId);
            if (s.equals("EXPIRED")) throw new SeatReservationException(SeatReservationException.Reason.HOLD_EXPIRED, "expired", null);
            if (s.equals("RELEASED")) throw new SeatReservationException(SeatReservationException.Reason.ILLEGAL_STATE_TRANSITION, "released", null);
            holds.put(holdId, "CONFIRMED");
        }
        @Override public void release(UUID holdId, UUID userId) {
            calls.add("release");
            if (holds.get(holdId).equals("HELD")) holds.put(holdId, "RELEASED");
        }
    };

    final BookingStore bookingStore = new BookingStore() {
        @Override public Booking insert(Booking b) {
            if (failInsert) throw new IllegalStateException("database down");
            Booking stored = copy(b, b.status(), 0);
            bookings.put(b.id(), stored);
            return stored;
        }
        @Override public Optional<Booking> find(UUID id) {
            Booking b = bookings.get(id);
            return Optional.ofNullable(b == null ? null : copy(b, b.status(), b.version()));
        }
        @Override public boolean updateStatus(Booking b) {
            Booking current = bookings.get(b.id());
            if (current.version() != b.version()) return false;
            bookings.put(b.id(), copy(b, b.status(), b.version() + 1));
            return true;
        }
        @Override public List<Booking> findByUser(UUID u, BookingStatus s, int offset, int limit) { return List.of(); }
        @Override public long countByUser(UUID u, BookingStatus s) { return 0; }
    };

    final SagaStore sagaStore = new SagaStore() {
        @Override public void create(UUID id, Instant next) { sagas.put(id, new Saga(id, SagaStep.AWAITING_PAYMENT, 0, next, null)); }
        @Override public Optional<Saga> find(UUID id) { return Optional.ofNullable(sagas.get(id)); }
        @Override public void moveTo(UUID id, SagaStep step, Instant next) { sagas.put(id, new Saga(id, step, 0, next, null)); }
        @Override public void retryLater(UUID id, String error, Instant next) {
            Saga s = sagas.get(id);
            sagas.put(id, new Saga(id, s.step(), s.attempts() + 1, next, error));
        }
        @Override public List<Saga> claimDue(int limit, Duration lease) { return List.of(); }
    };

    final Payments paymentPort = new Payments() {
        @Override public PaymentInfo createIntent(UUID bookingId, UUID userId, long amount, String currency) {
            if (failIntent) throw new IllegalStateException("payment down");
            payments.putIfAbsent(bookingId, Status.INITIATED);
            return info(bookingId);
        }
        @Override public Optional<PaymentInfo> find(UUID bookingId) {
            return payments.containsKey(bookingId) ? Optional.of(info(bookingId)) : Optional.empty();
        }
        @Override public Map<UUID, PaymentInfo> findAll(Collection<UUID> ids) { return Map.of(); }
        @Override public Status cancel(UUID bookingId) {
            if (payments.get(bookingId) == Status.INITIATED) payments.put(bookingId, Status.CANCELLED);
            return payments.get(bookingId);
        }
        @Override public void refund(UUID bookingId) { calls.add("refund"); payments.put(bookingId, Status.REFUNDED); }
        private PaymentInfo info(UUID bookingId) { return new PaymentInfo(bookingId, payments.get(bookingId), "MOCK", "o-" + bookingId); }
    };

    BookingSaga saga() {
        return new BookingSaga(sagaStore, new BookingTransitions(bookingStore), bookingStore, seats, paymentPort, clock());
    }

    CreateBookingUseCase createBooking() {
        return new CreateBookingUseCase(seats, bookingStore, sagaStore, saga(),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    }

    private static Booking copy(Booking b, BookingStatus status, long version) {
        return new Booking(b.id(), b.userId(), b.showId(), b.holdId(), status, b.seats(), b.amountMinor(), b.currency(),
                b.holdExpiresAt(), b.createdAt() == null ? Instant.EPOCH : b.createdAt(), version);
    }
}
