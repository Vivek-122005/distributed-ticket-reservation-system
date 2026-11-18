package com.vivek.ticketreservation.booking;

import com.vivek.ticketreservation.support.BookingRequestBuilder;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.vivek.ticketreservation.support.BookingRequestBuilder.aBooking;
import static com.vivek.ticketreservation.support.InventoryTestData.seatStatus;
import static org.assertj.core.api.Assertions.assertThat;

/** P3.7 cancel a pending booking, P3.8 booking history. */
@IntegrationTest
@SuppressWarnings({"rawtypes", "unchecked"})
class CancelAndHistoryIT {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void reset() { BookingTestData.reset(jdbc); }

    @Test
    void cancellingAPendingBookingReleasesItsSeats() {
        String id = book("A1", "A2");

        ResponseEntity<Map> cancelled = cancel(user, id, UUID.randomUUID().toString());

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).containsEntry("status", "CANCELLED").containsEntry("holdExpiresAt", null);
        assertThat(seatStatus(jdbc, "A1")).isEqualTo("AVAILABLE");
        assertThat(seatStatus(jdbc, "A2")).isEqualTo("AVAILABLE");
        assertThat(aBooking().seats("A1").post(http).getStatusCode()).as("seats are sellable again").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void cancelIsIdempotentEvenWithANewKey() {
        String id = book("A3");
        cancel(user, id, UUID.randomUUID().toString());

        ResponseEntity<Map> again = cancel(user, id, UUID.randomUUID().toString());

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("status", "CANCELLED");
    }

    @Test
    void aConfirmedBookingCannotBeCancelled() {
        String id = book("A4");
        jdbc.update("UPDATE booking.bookings SET status = 'CONFIRMED' WHERE id = ?::uuid", id);

        ResponseEntity<Map> response = cancel(user, id, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "ILLEGAL_STATE_TRANSITION");
    }

    @Test
    void cancelOfAHoldThatPaymentAlreadyConfirmedIsRefused() {
        String id = book("A5");
        jdbc.update("""
                UPDATE inventory.holds SET status = 'CONFIRMED'
                WHERE id = (SELECT hold_id FROM booking.bookings WHERE id = ?::uuid)""", id);
        jdbc.update("UPDATE inventory.show_seats SET status = 'BOOKED' WHERE seat_code = 'A5'");

        ResponseEntity<Map> response = cancel(user, id, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT status FROM booking.bookings WHERE id = ?::uuid", String.class, id))
                .as("still pending: the saga will confirm it").isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(jdbc, "A5")).isEqualTo("BOOKED");
    }

    @Test
    void anotherUserCannotCancel() {
        String id = book("A6");
        ResponseEntity<Map> response = cancel(UUID.randomUUID(), id, UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(seatStatus(jdbc, "A6")).isEqualTo("HELD");
    }

    @Test
    void cancelNeedsAnIdempotencyKey() {
        String id = book("A7");
        assertThat(cancel(user, id, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void historyIsNewestFirstPagedAndOnlyMine() {
        String first = book("A11");
        String second = book("A12");
        String third = book("A13");
        aBooking().seats("A14").post(http);                       // someone else's
        cancel(user, second, UUID.randomUUID().toString());

        Map page0 = history("?size=2").getBody();
        assertThat(page0).containsEntry("page", 0).containsEntry("size", 2).containsEntry("totalItems", 3);
        assertThat(ids(page0)).containsExactly(third, second);
        assertThat(ids(history("?size=2&page=1").getBody())).containsExactly(first);
        assertThat(ids(history("?status=CANCELLED").getBody())).containsExactly(second);
        assertThat(((List<Map>) page0.get("items")).get(0).get("seats")).isEqualTo(List.of(Map.of("seatCode", "A13", "priceMinor", 150000)));
    }

    @Test
    void historyRejectsOversizedPages() {
        assertThat(history("?size=51").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String book(String... seats) {
        BookingRequestBuilder request = aBooking().byUser(user).seats(seats);
        ResponseEntity<Map> response = request.post(http);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("bookingId");
    }

    private ResponseEntity<Map> cancel(UUID who, String bookingId, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", who.toString());
        if (key != null) headers.set("Idempotency-Key", key);
        return http.postForEntity("/api/v1/bookings/" + bookingId + "/cancel", new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> history(String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user.toString());
        return http.exchange("/api/v1/bookings" + query, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private static List<String> ids(Map page) {
        return ((List<Map>) page.get("items")).stream().map(m -> (String) m.get("bookingId")).toList();
    }
}
