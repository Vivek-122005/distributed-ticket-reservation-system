package com.vivek.ticketreservation.booking.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.vivek.ticketreservation.booking.domain.BookingStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The booking state machine (plan §4.4), without Spring or a database. */
class BookingTest {

    private static Booking pending() {
        return Booking.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new BookingSeat("A1", 150_000), new BookingSeat("A2", 150_000)), "INR",
                Instant.parse("2026-11-18T10:05:00Z"));
    }

    @Test
    void newBookingIsPendingAndCostsTheSumOfItsSeats() {
        Booking booking = pending();
        assertThat(booking.status()).isEqualTo(PENDING_PAYMENT);
        assertThat(booking.amountMinor()).isEqualTo(300_000);
    }

    @Test
    void happyPathConfirmsOnceAndRepeatsAreNoOps() {
        Booking booking = pending();
        assertThat(booking.markConfirmed()).isTrue();
        assertThat(booking.markConfirmed()).isFalse();
        assertThat(booking.status()).isEqualTo(CONFIRMED);
    }

    @Test
    void confirmedBookingCannotBeCancelledOrExpired() {
        Booking booking = pending();
        booking.markConfirmed();
        assertThatThrownBy(booking::cancel).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(booking::markExpired).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(booking.status()).isEqualTo(CONFIRMED);
    }

    @Test
    void latePaymentAfterExpiryGoesToRefund() {
        Booking booking = pending();
        booking.markExpired();
        assertThat(booking.requireRefund()).isTrue();
        assertThat(booking.markRefunded()).isTrue();
        assertThat(booking.status()).isEqualTo(REFUNDED);
    }

    @Test
    void paymentCapturedAfterCancelGoesToRefund() {
        Booking booking = pending();
        booking.cancel();
        assertThat(booking.requireRefund()).isTrue();
    }

    @Test
    void pendingBookingCannotBeRefunded() {
        assertThatThrownBy(pending()::requireRefund).isInstanceOf(IllegalStateTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "FAILED", "REFUNDED"})
    void terminalStatesAllowNothing(BookingStatus status) {
        assertThat(status.isTerminal()).isTrue();
        for (BookingStatus next : BookingStatus.values()) assertThat(status.canTransitionTo(next)).isFalse();
    }

    @Test
    void amountMustMatchSeatPrices() {
        assertThatThrownBy(() -> new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                PENDING_PAYMENT, List.of(new BookingSeat("A1", 100)), 99, "INR", Instant.now(), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bookingHasOneToEightSeats() {
        assertThatThrownBy(() -> Booking.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), List.of(), "INR", Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }
}
