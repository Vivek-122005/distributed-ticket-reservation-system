package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.Payments;
import com.vivek.ticketreservation.booking.domain.Booking;
import com.vivek.ticketreservation.booking.domain.BookingStatus;
import com.vivek.ticketreservation.booking.domain.SagaStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Every saga path (plan §4.5, P4.4–P4.5) with in-memory fakes: no Spring, no database, no waiting. */
class BookingSagaTest {
    private final Fakes fakes = new Fakes();
    private BookingSaga saga;
    private UUID id;

    @BeforeEach
    void book() {
        saga = fakes.saga();
        id = fakes.createBooking().execute(new CreateBookingUseCase.Command(UUID.randomUUID(), UUID.randomUUID(), List.of("A1"))).bookingId();
    }

    private Booking booking() { return fakes.bookings.get(id); }
    private SagaStep step() { return fakes.sagas.get(id).step(); }
    private void paymentIs(Payments.Status s) { fakes.payments.put(id, s); }

    @Test
    void captureConfirmsSeatsThenBooking() {
        paymentIs(Payments.Status.CAPTURED);
        saga.onPaymentCaptured(id);
        assertThat(fakes.holds.get(booking().holdId())).isEqualTo("CONFIRMED");
        assertThat(booking().status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(step()).isEqualTo(SagaStep.DONE);
    }

    @Test
    void aRepeatedCaptureIsHarmless() {
        paymentIs(Payments.Status.CAPTURED);
        saga.onPaymentCaptured(id);
        saga.onPaymentCaptured(id);
        assertThat(booking().status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(fakes.calls).doesNotContain("refund");
    }

    @Test
    void declineReleasesSeatsAndFailsTheBooking() {
        paymentIs(Payments.Status.FAILED);
        saga.onPaymentFailed(id);
        assertThat(fakes.holds.get(booking().holdId())).isEqualTo("RELEASED");
        assertThat(booking().status()).isEqualTo(BookingStatus.FAILED);
        assertThat(step()).isEqualTo(SagaStep.DONE);
    }

    @Test
    void capturedAfterTheHoldExpiredIsRefunded() {
        fakes.holds.put(booking().holdId(), "EXPIRED");
        paymentIs(Payments.Status.CAPTURED);
        saga.onPaymentCaptured(id);
        assertThat(booking().status()).isEqualTo(BookingStatus.REFUNDED);
        assertThat(fakes.payments.get(id)).isEqualTo(Payments.Status.REFUNDED);
        assertThat(step()).isEqualTo(SagaStep.DONE);
    }

    @Test
    void capturedAfterTheUserCancelledIsRefunded() {
        fakes.holds.put(booking().holdId(), "RELEASED");
        fakes.bookingStore.updateStatus(cancelled());
        paymentIs(Payments.Status.CAPTURED);
        saga.onPaymentCaptured(id);
        assertThat(booking().status()).isEqualTo(BookingStatus.REFUNDED);
    }

    @Test
    void unpaidBookingExpiresAtTheHoldsExpiryAndItsIntentIsWithdrawn() {
        fakes.now = booking().holdExpiresAt().plusSeconds(5);
        saga.resume(id, SagaStep.AWAITING_PAYMENT);
        assertThat(booking().status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(fakes.payments.get(id)).isEqualTo(Payments.Status.CANCELLED);
        assertThat(step()).isEqualTo(SagaStep.DONE);
    }

    @Test
    void aBookingIsNeverExpiredWhileItsPaymentIsInFlight() {
        paymentIs(Payments.Status.UNKNOWN);
        fakes.now = booking().holdExpiresAt().plusSeconds(5);
        saga.resume(id, SagaStep.AWAITING_PAYMENT);
        assertThat(booking().status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(step()).isEqualTo(SagaStep.AWAITING_PAYMENT);
        assertThat(fakes.sagas.get(id).nextRetryAt()).isAfter(fakes.now);
    }

    @Test
    void aPaymentCheckFindsACaptureWhoseEventWasLost() {
        paymentIs(Payments.Status.CAPTURED);   // captured, but nobody told the saga
        saga.resume(id, SagaStep.AWAITING_PAYMENT);
        assertThat(booking().status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void beforeExpiryAPaymentCheckJustSchedulesTheNextOne() {
        saga.resume(id, SagaStep.AWAITING_PAYMENT);
        assertThat(booking().status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(fakes.sagas.get(id).nextRetryAt()).isAfter(fakes.now);
    }

    @Test
    void aFailingStepIsRetriedLaterNotThrown() {
        paymentIs(Payments.Status.CAPTURED);
        fakes.holds.put(booking().holdId(), null);   // the next confirm call blows up (NPE): a transient failure
        saga.onPaymentCaptured(id);
        assertThat(step()).isEqualTo(SagaStep.CONFIRMING_SEATS);
        assertThat(fakes.sagas.get(id).attempts()).isEqualTo(1);

        fakes.holds.put(booking().holdId(), "HELD");   // recovered
        saga.resume(id, SagaStep.CONFIRMING_SEATS);
        assertThat(booking().status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    private Booking cancelled() {
        Booking b = fakes.bookingStore.find(id).orElseThrow();
        b.cancel();
        return b;
    }
}
