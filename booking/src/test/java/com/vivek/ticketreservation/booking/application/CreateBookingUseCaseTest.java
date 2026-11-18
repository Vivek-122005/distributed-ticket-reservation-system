package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.Payments;
import com.vivek.ticketreservation.booking.domain.BookingStatus;
import com.vivek.ticketreservation.booking.domain.SagaStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P3.3 + P4.4 compensation rules with in-memory fakes. */
class CreateBookingUseCaseTest {
    private final Fakes fakes = new Fakes();

    private BookingView book() {
        return fakes.createBooking().execute(new CreateBookingUseCase.Command(UUID.randomUUID(), UUID.randomUUID(), List.of("A1", "A2")));
    }

    @Test
    void savesAPendingBookingItsSagaAndAPaymentIntent() {
        BookingView view = book();
        assertThat(view.amountMinor()).isEqualTo(2000);
        assertThat(view.payment().status()).isEqualTo(Payments.Status.INITIATED);
        assertThat(fakes.sagas.get(view.bookingId()).step()).isEqualTo(SagaStep.AWAITING_PAYMENT);
        assertThat(fakes.calls).doesNotContain("release");
    }

    @Test
    void releasesTheHoldWhenTheBookingCannotBeSaved() {
        fakes.failInsert = true;
        assertThatThrownBy(this::book).hasMessage("database down");
        assertThat(fakes.holds.values()).containsExactly("RELEASED");
        assertThat(fakes.sagas).isEmpty();
    }

    @Test
    void failsTheBookingAndFreesTheSeatsWhenPaymentIsDown() {
        fakes.failIntent = true;
        assertThatThrownBy(this::book).isInstanceOf(PaymentUnavailableException.class);
        assertThat(fakes.holds.values()).containsExactly("RELEASED");
        assertThat(fakes.bookings.values()).singleElement().extracting(b -> b.status()).isEqualTo(BookingStatus.FAILED);
        assertThat(fakes.sagas.values()).singleElement().extracting(s -> s.step()).isEqualTo(SagaStep.DONE);
    }
}
