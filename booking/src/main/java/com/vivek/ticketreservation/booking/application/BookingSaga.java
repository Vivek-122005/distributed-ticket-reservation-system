package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.BookingStore;
import com.vivek.ticketreservation.booking.application.port.Payments;
import com.vivek.ticketreservation.booking.application.port.Payments.PaymentInfo;
import com.vivek.ticketreservation.booking.application.port.SagaStore;
import com.vivek.ticketreservation.booking.application.port.SeatReservationException;
import com.vivek.ticketreservation.booking.application.port.SeatReservationException.Reason;
import com.vivek.ticketreservation.booking.application.port.SeatReservations;
import com.vivek.ticketreservation.booking.domain.Booking;
import com.vivek.ticketreservation.booking.domain.BookingStatus;
import com.vivek.ticketreservation.booking.domain.SagaStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates one booking across inventory and payment (plan §4.5, P4.4–P4.5): hold → payment intent
 * → await capture → confirm seats → booking CONFIRMED, with compensations.
 *
 * <p>Pattern: Saga, orchestrated (docs/lld/DESIGN_PATTERNS.md#10, ADR 004). The saga decides <i>what
 * happens next</i>; {@link Booking} decides which transitions are legal (SRP). Three rules make it
 * crash-safe:
 * <ol>
 *   <li><b>Save the step, then do it.</b> The step is persisted in {@code booking_sagas} before its
 *       remote call, so after a crash the recovery job repeats exactly that step.</li>
 *   <li><b>Every step is idempotent</b> (confirm, release, refund, cancel and every booking transition),
 *       so repeating one is always safe.</li>
 *   <li><b>A failure is a retry, not an exception to the caller.</b> Transient errors are recorded with
 *       a backoff; the recovery job picks the saga up again.</li>
 * </ol>
 *
 * <p>Paths: captured → confirm (or, if the hold expired or was released first, the late-payment path
 * {@code REFUND_PENDING → REFUNDED}); failed → release seats, booking FAILED; no payment by the hold's
 * expiry → cancel the intent, booking EXPIRED; payment still in flight at expiry (PROCESSING / UNKNOWN)
 * → wait for its outcome, which reconciliation (P4.8) guarantees.
 */
@Component
public class BookingSaga {
    private static final Logger log = LoggerFactory.getLogger(BookingSaga.class);
    /** How often a saga waiting for payment re-checks it (covers a lost PaymentCaptured event). */
    static final Duration PAYMENT_CHECK = Duration.ofSeconds(30);
    /** If nothing completes a started step, recovery looks again after this. */
    static final Duration STEP_TIMEOUT = Duration.ofSeconds(30);
    /** Grace after hold expiry before the booking is expired: lets a just-started checkout finish. */
    static final Duration EXPIRY_GRACE = Duration.ofSeconds(2);

    private final SagaStore sagas;
    private final BookingTransitions transitions;
    private final BookingStore bookings;
    private final SeatReservations seats;
    private final Payments payments;
    private final Clock clock;

    public BookingSaga(SagaStore sagas, BookingTransitions transitions,
                       BookingStore bookings,
                       SeatReservations seats, Payments payments, Clock clock) {
        this.sagas = sagas;
        this.transitions = transitions;
        this.bookings = bookings;
        this.seats = seats;
        this.payments = payments;
        this.clock = clock;
    }

    /** First check of a new saga: the earlier of a payment re-check and the hold's expiry. */
    public Instant firstCheck(Instant holdExpiresAt) { return min(clock.instant().plus(PAYMENT_CHECK), holdExpiresAt.plus(EXPIRY_GRACE)); }

    /**
     * Creates the payment intent of a new booking. If that fails the booking can't be paid, so it is
     * compensated at once (seats released, booking FAILED) and the caller gets a retryable error.
     */
    public PaymentInfo start(Booking booking) {
        try {
            return payments.createIntent(booking.id(), booking.userId(), booking.amountMinor(), booking.currency());
        } catch (RuntimeException e) {
            log.warn("Payment intent for booking {} failed; compensating", booking.id(), e);
            releaseSeats(booking);
            transitions.apply(booking.id(), b -> b.status() == BookingStatus.PENDING_PAYMENT && b.markFailed());
            sagas.moveTo(booking.id(), SagaStep.DONE, clock.instant());
            throw new PaymentUnavailableException("Payment is unavailable, retry with backoff", e);
        }
    }

    /** The money was taken (event, webhook or reconciliation). */
    public void onPaymentCaptured(UUID bookingId) {
        sagas.moveTo(bookingId, SagaStep.CONFIRMING_SEATS, clock.instant().plus(STEP_TIMEOUT));
        confirmSeats(bookingId);
    }

    /** The payment definitely failed. */
    public void onPaymentFailed(UUID bookingId) {
        sagas.moveTo(bookingId, SagaStep.RELEASING_SEATS, clock.instant().plus(STEP_TIMEOUT));
        releaseAfterFailure(bookingId);
    }

    /** The user cancelled (P3.7): if the intent could be withdrawn too, nothing is left to wait for. */
    public void onCancelled(UUID bookingId) {
        try {
            if (payments.cancel(bookingId) == Payments.Status.CANCELLED) sagas.moveTo(bookingId, SagaStep.DONE, clock.instant());
            // Otherwise a payment is in flight: its outcome arrives as an event and goes to the refund path.
        } catch (RuntimeException e) {
            log.warn("Could not cancel the payment of booking {}; the saga will check again", bookingId, e);
        }
    }

    /** Called by the recovery job for a due saga: repeat the saved step. */
    public void resume(UUID bookingId, SagaStep step) {
        switch (step) {
            case AWAITING_PAYMENT -> checkPayment(bookingId);
            case CONFIRMING_SEATS -> confirmSeats(bookingId);
            case RELEASING_SEATS -> releaseAfterFailure(bookingId);
            case REFUNDING -> refund(bookingId);
            case DONE -> { }
        }
    }

    // ---------------------------------------------------------------- steps

    private void checkPayment(UUID bookingId) {
        guarded(bookingId, () -> {
            Booking booking = load(bookingId);
            Optional<PaymentInfo> payment = payments.find(bookingId);
            Payments.Status status = payment.map(PaymentInfo::status).orElse(null);
            if (status == Payments.Status.CAPTURED) { onPaymentCaptured(bookingId); return; }
            if (status == Payments.Status.FAILED) { onPaymentFailed(bookingId); return; }
            if (booking.status() != BookingStatus.PENDING_PAYMENT) {           // cancelled, nothing in flight
                sagas.moveTo(bookingId, SagaStep.DONE, clock.instant());
                return;
            }
            Instant now = clock.instant();
            boolean expired = now.isAfter(booking.holdExpiresAt().plus(EXPIRY_GRACE));
            boolean inFlight = status == Payments.Status.PROCESSING || status == Payments.Status.UNKNOWN;
            if (!expired || inFlight) {
                // Not due yet, or the payment's outcome is still unknown: never expire a booking that may be paid.
                sagas.moveTo(bookingId, SagaStep.AWAITING_PAYMENT, expired ? now.plus(PAYMENT_CHECK) : firstCheck(booking.holdExpiresAt()));
                return;
            }
            expire(booking);
        });
    }

    /** P4.5: nobody paid before the hold expired. */
    private void expire(Booking booking) {
        Payments.Status after = payments.find(booking.id()).isPresent() ? payments.cancel(booking.id()) : Payments.Status.CANCELLED;
        if (after != Payments.Status.CANCELLED) {
            // The user started paying at the last moment: wait for that payment's outcome.
            sagas.moveTo(booking.id(), SagaStep.AWAITING_PAYMENT, clock.instant().plus(PAYMENT_CHECK));
            return;
        }
        releaseSeats(booking);   // normally a no-op: inventory already expired the hold
        transitions.apply(booking.id(), b -> b.status() == BookingStatus.PENDING_PAYMENT && b.markExpired());
        sagas.moveTo(booking.id(), SagaStep.DONE, clock.instant());
        log.info("Booking {} expired unpaid", booking.id());
    }

    private void confirmSeats(UUID bookingId) {
        guarded(bookingId, () -> {
            Booking booking = load(bookingId);
            if (booking.status() == BookingStatus.CONFIRMED) { sagas.moveTo(bookingId, SagaStep.DONE, clock.instant()); return; }
            if (booking.status() != BookingStatus.PENDING_PAYMENT) { startRefund(booking); return; }   // cancelled / expired first
            try {
                seats.confirm(booking.holdId(), booking.userId());
            } catch (SeatReservationException e) {
                if (e.reason() == Reason.HOLD_EXPIRED) {
                    transitions.apply(bookingId, b -> b.status() == BookingStatus.PENDING_PAYMENT && b.markExpired());
                    startRefund(load(bookingId));
                    return;
                }
                if (e.reason() == Reason.ILLEGAL_STATE_TRANSITION || e.reason() == Reason.HOLD_NOT_FOUND) {
                    startRefund(load(bookingId));   // the hold was released (cancel won): give the money back
                    return;
                }
                throw e;
            }
            transitions.apply(bookingId, Booking::markConfirmed);
            sagas.moveTo(bookingId, SagaStep.DONE, clock.instant());
            log.info("Booking {} confirmed", bookingId);
        });
    }

    /** P4.5 late-payment path: money arrived for seats we can't give, so it goes back. */
    private void startRefund(Booking booking) {
        if (booking.status() == BookingStatus.REFUNDED) { sagas.moveTo(booking.id(), SagaStep.DONE, clock.instant()); return; }
        transitions.apply(booking.id(), b -> b.status() != BookingStatus.REFUND_PENDING && b.requireRefund());
        sagas.moveTo(booking.id(), SagaStep.REFUNDING, clock.instant().plus(STEP_TIMEOUT));
        refund(booking.id());
    }

    private void refund(UUID bookingId) {
        guarded(bookingId, () -> {
            payments.refund(bookingId);
            transitions.apply(bookingId, Booking::markRefunded);
            sagas.moveTo(bookingId, SagaStep.DONE, clock.instant());
            log.info("Booking {} refunded (late payment)", bookingId);
        });
    }

    private void releaseAfterFailure(UUID bookingId) {
        guarded(bookingId, () -> {
            Booking booking = load(bookingId);
            if (booking.status() == BookingStatus.PENDING_PAYMENT) {
                releaseSeats(booking);
                transitions.apply(bookingId, b -> b.status() == BookingStatus.PENDING_PAYMENT && b.markFailed());
            }
            sagas.moveTo(bookingId, SagaStep.DONE, clock.instant());
        });
    }

    private void releaseSeats(Booking booking) {
        try {
            seats.release(booking.holdId(), booking.userId());
        } catch (SeatReservationException e) {
            if (e.reason() != Reason.HOLD_NOT_FOUND) throw e;
        }
    }

    // ---------------------------------------------------------------- plumbing

    /** Runs a step; a failure is recorded with exponential backoff (1 s … 60 s) instead of propagating. */
    private void guarded(UUID bookingId, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            int attempts = sagas.find(bookingId).map(SagaStore.Saga::attempts).orElse(0);
            Duration backoff = Duration.ofSeconds(Math.min(60, 1L << Math.min(attempts, 6)));
            sagas.retryLater(bookingId, e.toString(), clock.instant().plus(backoff));
            log.warn("Saga step for booking {} failed (attempt {}); retrying in {}", bookingId, attempts + 1, backoff, e);
        }
    }

    private Booking load(UUID bookingId) {
        return bookings.find(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
