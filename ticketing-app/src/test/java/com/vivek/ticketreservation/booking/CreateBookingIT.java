package com.vivek.ticketreservation.booking;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.vivek.ticketreservation.support.BookingTestData.bookings;
import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static com.vivek.ticketreservation.support.InventoryTestData.seatStatus;
import static org.assertj.core.api.Assertions.assertThat;

/** P3.3: a booking holds its seats through the inventory port and starts in PENDING_PAYMENT. */
@IntegrationTest
class CreateBookingIT {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() { BookingTestData.reset(jdbc); }

    @Test
    void createsAPendingBookingAndHoldsItsSeats() {
        UUID user = UUID.randomUUID();
        ResponseEntity<Map> response = create(user, DEMO_SHOW, "A1", "A2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("status", "PENDING_PAYMENT").containsEntry("amountMinor", 300000)
                .containsEntry("currency", "INR").containsKey("holdExpiresAt");
        assertThat(body.get("seats")).isEqualTo(List.of(
                Map.of("seatCode", "A1", "priceMinor", 150000), Map.of("seatCode", "A2", "priceMinor", 150000)));
        assertThat(response.getHeaders().getLocation()).hasToString("/api/v1/bookings/" + body.get("bookingId"));
        assertThat(seatStatus(jdbc, "A1")).isEqualTo("HELD");

        ResponseEntity<Map> fetched = get(user, (String) body.get("bookingId"));
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).containsEntry("status", "PENDING_PAYMENT").containsEntry("amountMinor", 300000);
    }

    @Test
    void takenSeatCreatesNoBooking() {
        create(UUID.randomUUID(), DEMO_SHOW, "A5");

        ResponseEntity<Map> second = create(UUID.randomUUID(), DEMO_SHOW, "A5", "A6");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("code", "SEAT_UNAVAILABLE").containsEntry("retryable", false);
        assertThat(bookings(jdbc)).isEqualTo(1);
        assertThat(seatStatus(jdbc, "A6")).isEqualTo("AVAILABLE");
    }

    @Test
    void unknownShowIsNotFound() {
        ResponseEntity<Map> response = create(UUID.randomUUID(), UUID.randomUUID(), "A1");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "SHOW_NOT_FOUND");
    }

    @Test
    void anotherUsersBookingIsNotFound() {
        String id = (String) create(UUID.randomUUID(), DEMO_SHOW, "A7").getBody().get("bookingId");
        ResponseEntity<Map> response = get(UUID.randomUUID(), id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "BOOKING_NOT_FOUND");
    }

    @Test
    void tooManySeatsIsInvalid() {
        ResponseEntity<Map> response = create(UUID.randomUUID(), DEMO_SHOW, "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "INVALID_REQUEST");
        assertThat(bookings(jdbc)).isZero();
    }

    private ResponseEntity<Map> create(UUID user, UUID show, String... seats) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", user.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.postForEntity("/api/v1/bookings", new HttpEntity<>(Map.of("showId", show, "seatCodes", List.of(seats)), headers), Map.class);
    }

    private ResponseEntity<Map> get(UUID user, String bookingId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user.toString());
        return http.exchange("/api/v1/bookings/" + bookingId, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
