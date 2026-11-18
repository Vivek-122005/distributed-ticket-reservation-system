package com.vivek.ticketreservation.booking;

import com.vivek.ticketreservation.support.BookingRequestBuilder;
import com.vivek.ticketreservation.support.BookingTestData;
import com.vivek.ticketreservation.support.Contention;
import com.vivek.ticketreservation.support.Contention.Outcome;
import com.vivek.ticketreservation.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.vivek.ticketreservation.support.BookingRequestBuilder.aBooking;
import static com.vivek.ticketreservation.support.BookingTestData.bookings;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3.6: exactly-once booking under concurrency. Duplicates of one request (same user, same key) must
 * produce one booking; different users competing for the same seats must produce one booking.
 */
@IntegrationTest
class BookingConcurrencyIT {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @BeforeEach
    void reset() { BookingTestData.reset(jdbc); }

    @Test
    @SuppressWarnings("rawtypes")
    void fiftyConcurrentDuplicatesCreateOneBooking() throws Exception {
        BookingRequestBuilder request = aBooking().seats("A1", "A2");

        List<Outcome<ResponseEntity<Map>>> outcomes = Contention.race(50, i -> request.post(http));

        List<ResponseEntity<Map>> responses = outcomes.stream().map(Outcome::value).toList();
        Set<Object> bookingIds = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                .map(r -> r.getBody().get("bookingId")).collect(Collectors.toSet());
        assertThat(bookingIds).as("every 201 is the same booking").hasSize(1);
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT));
        assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT))
                .allSatisfy(r -> assertThat(r.getBody()).containsEntry("code", "IDEMPOTENCY_IN_PROGRESS"));
        assertThat(bookings(jdbc)).isEqualTo(1);
        assertThat(heldSeats()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void clientThatTimedOutGetsTheOriginalAnswerOnRetry() throws Exception {
        BookingRequestBuilder request = aBooking().seats("A3");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder impatient = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bookings"))
                .timeout(Duration.ofMillis(1)).POST(HttpRequest.BodyPublishers.ofString(request.json()));
        request.headers().forEach((name, values) -> values.forEach(v -> impatient.header(name, v)));
        try {
            client.send(impatient.build(), HttpResponse.BodyHandlers.discarding());
        } catch (java.net.http.HttpTimeoutException expected) {
            // The client gave up; the server may still be working on it.
        }

        ResponseEntity<Map> retry = request.post(http);
        for (int i = 0; i < 50 && retry.getStatusCode() == HttpStatus.CONFLICT; i++) {
            Thread.sleep(100);   // Retry-After: the first attempt is still running
            retry = request.post(http);
        }

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bookings(jdbc)).as("the retry did not book a second time").isEqualTo(1);
        assertThat(retry.getBody().get("bookingId")).isEqualTo(onlyBookingId());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void twoHundredUsersRacingForTheSameSeatsGetOneBooking() throws Exception {
        List<Outcome<ResponseEntity<Map>>> outcomes =
                Contention.race(200, i -> aBooking().seats("A10", "A11").post(http));

        long created = outcomes.stream().filter(o -> o.value().getStatusCode() == HttpStatus.CREATED).count();
        assertThat(created).isEqualTo(1);
        assertThat(outcomes).allSatisfy(o -> {
            if (o.value().getStatusCode() != HttpStatus.CREATED) {
                assertThat(o.value().getBody()).containsEntry("code", "SEAT_UNAVAILABLE");
            }
        });
        assertThat(bookings(jdbc)).isEqualTo(1);
        assertThat(heldSeats()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void manyUsersEachRetryingTheirOwnRequestGetOneBookingEach() throws Exception {
        List<BookingRequestBuilder> users = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> aBooking().byUser(UUID.randomUUID()).seats("A" + (30 + i))).toList();

        Contention.race(100, i -> users.get(i % 20).post(http));   // 5 duplicates per user

        assertThat(bookings(jdbc)).isEqualTo(20);
        Long perUser = jdbc.queryForObject("SELECT max(c) FROM (SELECT count(*) c FROM booking.bookings GROUP BY user_id) x", Long.class);
        assertThat(perUser).isEqualTo(1);
    }

    private int heldSeats() {
        Integer held = jdbc.queryForObject("""
                SELECT count(*) FROM inventory.show_seats s JOIN booking.bookings b ON b.hold_id = s.hold_id
                WHERE s.status = 'HELD'""", Integer.class);
        return held == null ? 0 : held;
    }

    private String onlyBookingId() {
        return jdbc.queryForObject("SELECT id::text FROM booking.bookings", String.class);
    }
}
