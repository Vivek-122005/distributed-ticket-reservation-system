package com.vivek.ticketreservation.booking;

import com.vivek.ticketreservation.booking.infrastructure.SagaRecoveryJob;
import com.vivek.ticketreservation.support.BookingTestData;
import com.vivek.ticketreservation.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static com.vivek.ticketreservation.support.BookingRequestBuilder.aBooking;
import static com.vivek.ticketreservation.support.BookingTestData.*;
import static com.vivek.ticketreservation.support.InventoryTestData.seatStatus;
import static org.assertj.core.api.Assertions.assertThat;

/** P4.4–P4.5 end to end over HTTP: book → pay → confirmed, and every compensation path. */
@IntegrationTest
@SuppressWarnings({"rawtypes", "unchecked"})
class BookingSagaIT {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SagaRecoveryJob recovery;

    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void reset() { BookingTestData.reset(jdbc); }

    @Test
    void bookPayConfirm() {
        Map booking = book("A1", "A2");
        assertThat(booking).containsEntry("status", "PENDING_PAYMENT");
        Map payment = (Map) booking.get("payment");
        assertThat(payment).containsEntry("status", "INITIATED").containsEntry("gateway", "MOCK");

        Map paid = pay(payment, "SUCCESS");

        assertThat(paid).containsEntry("status", "CAPTURED");
        Map after = get(booking);
        assertThat(after).containsEntry("status", "CONFIRMED").containsEntry("holdExpiresAt", null);
        assertThat(((Map) after.get("payment"))).containsEntry("status", "CAPTURED");
        assertThat(seatStatus(jdbc, "A1")).isEqualTo("BOOKED");
        assertThat(seatStatus(jdbc, "A2")).isEqualTo("BOOKED");
        assertThat(sagaStep(jdbc, booking.get("bookingId"))).isEqualTo("DONE");
    }

    @Test
    void declinedPaymentFailsTheBookingAndFreesTheSeats() {
        Map booking = book("A3");
        pay((Map) booking.get("payment"), "DECLINE");

        assertThat(get(booking)).containsEntry("status", "FAILED");
        assertThat(seatStatus(jdbc, "A3")).isEqualTo("AVAILABLE");
    }

    @Test
    void aLostGatewayAnswerKeepsTheSeatsHeld() {
        Map booking = book("A4");
        Map paid = pay((Map) booking.get("payment"), "TIMEOUT");

        assertThat(paid).containsEntry("status", "UNKNOWN");
        assertThat(get(booking)).containsEntry("status", "PENDING_PAYMENT");
        assertThat(seatStatus(jdbc, "A4")).as("never release seats that may be paid for").isEqualTo("HELD");
    }

    @Test
    void cancellingWithdrawsThePaymentIntent() {
        Map booking = book("A5");
        HttpHeaders headers = headers();
        http.postForEntity("/api/v1/bookings/" + booking.get("bookingId") + "/cancel", new HttpEntity<>(headers), Map.class);

        assertThat(paymentStatus(jdbc, booking.get("bookingId"))).isEqualTo("CANCELLED");
        assertThat(sagaStep(jdbc, booking.get("bookingId"))).isEqualTo("DONE");
        ResponseEntity<Map> late = payResponse((Map) booking.get("payment"), "SUCCESS");
        assertThat(late.getStatusCode()).as("a cancelled booking can't be paid").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anUnpaidBookingExpiresWithItsHold() {
        Map booking = book("A6");
        expireHold(jdbc, booking.get("bookingId"));

        recovery.runOnce(100);

        assertThat(bookingStatus(jdbc, booking.get("bookingId"))).isEqualTo("EXPIRED");
        assertThat(paymentStatus(jdbc, booking.get("bookingId"))).isEqualTo("CANCELLED");
        assertThat(seatStatus(jdbc, "A6")).isIn("AVAILABLE", "HELD");   // HELD-but-expired is claimable (lazy expiry)
        assertThat(aBooking().seats("A6").post(http).getStatusCode()).as("the seat is sellable again").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aPaymentThatArrivesAfterExpiryIsRefunded() {
        Map booking = book("A7");
        expireHold(jdbc, booking.get("bookingId"));

        Map paid = pay((Map) booking.get("payment"), "SUCCESS");   // the user paid just too late

        // Captured, then refunded by the saga before the checkout call even returned (synchronous events).
        assertThat(paid).containsEntry("status", "REFUNDED");
        assertThat(bookingStatus(jdbc, booking.get("bookingId"))).isEqualTo("REFUNDED");
        assertThat(paymentStatus(jdbc, booking.get("bookingId"))).isEqualTo("REFUNDED");
        assertThat(seatStatus(jdbc, "A7")).isNotEqualTo("BOOKED");
    }

    private Map book(String... seats) {
        ResponseEntity<Map> response = aBooking().byUser(user).seats(seats).post(http);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Map get(Map booking) {
        return http.exchange("/api/v1/bookings/" + booking.get("bookingId"), HttpMethod.GET, new HttpEntity<>(headers()), Map.class).getBody();
    }

    private Map pay(Map payment, String instruction) { return payResponse(payment, instruction).getBody(); }

    private ResponseEntity<Map> payResponse(Map payment, String instruction) {
        HttpHeaders headers = headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/api/v1/payments/" + payment.get("paymentId") + "/checkout",
                new HttpEntity<>("{\"instruction\":\"" + instruction + "\"}", headers), Map.class);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }
}
