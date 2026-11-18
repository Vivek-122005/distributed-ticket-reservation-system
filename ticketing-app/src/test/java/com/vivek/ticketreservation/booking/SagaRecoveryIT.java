package com.vivek.ticketreservation.booking;

import com.vivek.ticketreservation.booking.infrastructure.SagaRecoveryJob;
import com.vivek.ticketreservation.payment.application.ReconcilePaymentsUseCase;
import com.vivek.ticketreservation.payment.application.ReconcilePaymentsUseCase.Outcome;
import com.vivek.ticketreservation.support.BookingTestData;
import com.vivek.ticketreservation.support.Contention;
import com.vivek.ticketreservation.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.vivek.ticketreservation.support.BookingRequestBuilder.aBooking;
import static com.vivek.ticketreservation.support.BookingTestData.*;
import static com.vivek.ticketreservation.support.InventoryTestData.seatStatus;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4.6 crash recovery and P4.8 payment reconciliation. A crash is simulated by putting the database in
 * the exact state a dead process leaves behind (a step saved but not finished, an event never
 * delivered), then letting the recovery job or reconciliation run, as a restarted replica would.
 */
@IntegrationTest
@SuppressWarnings("rawtypes")
class SagaRecoveryIT {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SagaRecoveryJob recovery;
    @Autowired ReconcilePaymentsUseCase reconciliation;

    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void reset() { BookingTestData.reset(jdbc); }

    @Test
    void crashAfterCaptureBeforeConfirmStillConverges() {
        // The process died right after the gateway took the money: payment CAPTURED, event never delivered.
        String id = book("A1");
        jdbc.update("UPDATE payment.payments SET status = 'CAPTURED' WHERE booking_id = ?::uuid", id);
        due(id);

        recovery.runOnce(100);

        assertThat(bookingStatus(jdbc, id)).isEqualTo("CONFIRMED");
        assertThat(seatStatus(jdbc, "A1")).isEqualTo("BOOKED");
        assertThat(sagaStep(jdbc, id)).isEqualTo("DONE");
    }

    @Test
    void crashInTheMiddleOfConfirmingRepeatsThatStep() {
        String id = book("A2");
        jdbc.update("UPDATE payment.payments SET status = 'CAPTURED' WHERE booking_id = ?::uuid", id);
        jdbc.update("UPDATE booking.booking_sagas SET step = 'CONFIRMING_SEATS' WHERE booking_id = ?::uuid", id);
        due(id);

        recovery.runOnce(100);

        assertThat(bookingStatus(jdbc, id)).isEqualTo("CONFIRMED");
    }

    @Test
    void crashAfterSeatsWereConfirmedFinishesWithoutConfirmingTwice() {
        String id = book("A3");
        payViaHttp(id, "SUCCESS");
        // Pretend the process died after inventory confirmed but before the booking was marked CONFIRMED.
        jdbc.update("UPDATE booking.bookings SET status = 'PENDING_PAYMENT' WHERE id = ?::uuid", id);
        jdbc.update("UPDATE booking.booking_sagas SET step = 'CONFIRMING_SEATS' WHERE booking_id = ?::uuid", id);
        due(id);

        recovery.runOnce(100);

        assertThat(bookingStatus(jdbc, id)).isEqualTo("CONFIRMED");
        assertThat(seatStatus(jdbc, "A3")).isEqualTo("BOOKED");
    }

    @Test
    void crashDuringARefundRetriesTheRefund() {
        String id = book("A4");
        payViaHttp(id, "SUCCESS");
        jdbc.update("UPDATE booking.bookings SET status = 'REFUND_PENDING' WHERE id = ?::uuid", id);
        jdbc.update("UPDATE booking.booking_sagas SET step = 'REFUNDING' WHERE booking_id = ?::uuid", id);
        due(id);

        recovery.runOnce(100);

        assertThat(bookingStatus(jdbc, id)).isEqualTo("REFUNDED");
        assertThat(paymentStatus(jdbc, id)).isEqualTo("REFUNDED");
    }

    @Test
    void fourReplicasRecoveringFiftySagasConfirmEachOnce() throws Exception {
        List<String> ids = IntStream.range(0, 50).mapToObj(i -> book("A" + (50 + i))).toList();
        jdbc.update("UPDATE payment.payments SET status = 'CAPTURED'");
        jdbc.update("UPDATE booking.booking_sagas SET next_retry_at = now() - interval '1 second'");

        List<Contention.Outcome<Integer>> claimed = Contention.race(4, i -> recovery.runOnce(100));

        assertThat(claimed.stream().mapToInt(Contention.Outcome::value).sum()).as("each saga claimed by one replica").isEqualTo(50);
        assertThat(ids).allSatisfy(id -> assertThat(bookingStatus(jdbc, id)).isEqualTo("CONFIRMED"));
        Long booked = jdbc.queryForObject("SELECT count(*) FROM inventory.show_seats WHERE status = 'BOOKED'", Long.class);
        assertThat(booked).isEqualTo(50);
    }

    @Test
    void reconciliationResolvesALostGatewayAnswerAndTheBookingIsConfirmed() {
        String id = book("A5");
        payViaHttp(id, "TIMEOUT");
        assertThat(paymentStatus(jdbc, id)).isEqualTo("UNKNOWN");

        Map<Outcome, Integer> result = reconciliation.reconcile(Duration.ZERO, 100);

        assertThat(result).containsEntry(Outcome.CAPTURED, 1);
        assertThat(paymentStatus(jdbc, id)).isEqualTo("CAPTURED");
        assertThat(bookingStatus(jdbc, id)).as("the saga continued from the reconciled outcome").isEqualTo("CONFIRMED");
        assertThat(seatStatus(jdbc, "A5")).isEqualTo("BOOKED");
    }

    @Test
    void reconciliationFailsAPaymentTheGatewayNeverCharged() {
        // Crash between committing PROCESSING and calling the gateway: no money moved.
        String id = book("A6");
        jdbc.update("UPDATE payment.payments SET status = 'PROCESSING' WHERE booking_id = ?::uuid", id);

        reconciliation.reconcile(Duration.ZERO, 100);

        assertThat(paymentStatus(jdbc, id)).isEqualTo("FAILED");
        assertThat(bookingStatus(jdbc, id)).isEqualTo("FAILED");
        assertThat(seatStatus(jdbc, "A6")).isEqualTo("AVAILABLE");
    }

    @Test
    void reconciliationLeavesRecentPaymentsAlone() {
        String id = book("A7");
        payViaHttp(id, "TIMEOUT");

        assertThat(reconciliation.reconcile(Duration.ofMinutes(5), 100)).isEmpty();
        assertThat(paymentStatus(jdbc, id)).isEqualTo("UNKNOWN");
    }

    private String book(String seat) {
        ResponseEntity<Map> response = aBooking().byUser(user).seats(seat).post(http);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("bookingId");
    }

    private void due(String bookingId) {
        jdbc.update("UPDATE booking.booking_sagas SET next_retry_at = now() - interval '1 second' WHERE booking_id = ?::uuid", bookingId);
    }

    private void payViaHttp(String bookingId, String instruction) {
        String paymentId = jdbc.queryForObject("SELECT id::text FROM payment.payments WHERE booking_id = ?::uuid", String.class, bookingId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", user.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        http.postForEntity("/api/v1/payments/" + paymentId + "/checkout", new HttpEntity<>("{\"instruction\":\"" + instruction + "\"}", headers), Map.class);
    }
}
