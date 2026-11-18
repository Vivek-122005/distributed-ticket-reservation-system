package com.vivek.ticketreservation.booking;

import com.vivek.ticketreservation.common.idempotency.IdempotencyStore;
import com.vivek.ticketreservation.common.idempotency.IdempotencyStore.Outcome;
import com.vivek.ticketreservation.support.BookingTestData;
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

import static com.vivek.ticketreservation.support.BookingTestData.bookings;
import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;

/** P3.4–P3.5: the shared idempotency filter in front of POST /bookings (API contract in booking-service.yaml). */
@IntegrationTest
class IdempotencyIT {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdempotencyStore bookingIdempotencyStore;

    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void reset() { BookingTestData.reset(jdbc); }

    @Test
    void retryWithTheSameKeyReplaysTheFirstAnswer() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = post(user, key, "A1", "A2");
        ResponseEntity<Map> retry = post(user, key, "A1", "A2");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(retry.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(bookings(jdbc)).as("one booking, not two").isEqualTo(1);
    }

    @Test
    void rejectionsAreReplayedToo() {
        post(UUID.randomUUID(), UUID.randomUUID().toString(), "A3");
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = post(user, key, "A3");
        jdbc.update("UPDATE inventory.show_seats SET status = 'AVAILABLE', hold_id = NULL, held_by_user_id = NULL, hold_expires_at = NULL WHERE seat_code = 'A3'");

        ResponseEntity<Map> retry = post(user, key, "A3");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(retry.getStatusCode()).as("the stored answer, even though A3 is free now").isEqualTo(HttpStatus.CONFLICT);
        assertThat(retry.getBody()).containsEntry("code", "SEAT_UNAVAILABLE");
    }

    @Test
    void sameKeyWithADifferentBodyIsRejected() {
        String key = UUID.randomUUID().toString();
        post(user, key, "A4");
        ResponseEntity<Map> reused = post(user, key, "A5");

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reused.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED").containsEntry("retryable", false);
        assertThat(bookings(jdbc)).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerUser() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> mine = post(user, key, "A6");
        ResponseEntity<Map> theirs = post(UUID.randomUUID(), key, "A7");

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(theirs.getBody().get("bookingId")).isNotEqualTo(mine.getBody().get("bookingId"));
    }

    @Test
    void missingKeyIsInvalid() {
        ResponseEntity<Map> response = post(user, null, "A8");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "INVALID_REQUEST");
        assertThat(bookings(jdbc)).isZero();
    }

    @Test
    void retryWhileTheFirstIsRunningIsToldToWait() {
        String key = UUID.randomUUID().toString();
        assertThat(bookingIdempotencyStore.begin(user, key, hashOf("A9"), Duration.ofHours(1), Duration.ofMinutes(5)))
                .isInstanceOf(Outcome.Started.class);

        ResponseEntity<Map> retry = post(user, key, "A9");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(retry.getBody()).containsEntry("code", "IDEMPOTENCY_IN_PROGRESS").containsEntry("retryable", true);
        assertThat(retry.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void aClaimWhoseLeaseLapsedCanBeTakenOverByTheSameRequest() {
        String key = UUID.randomUUID().toString();
        bookingIdempotencyStore.begin(user, key, "h1", Duration.ofHours(1), Duration.ofSeconds(30));
        jdbc.update("UPDATE booking.idempotency_keys SET updated_at = now() - interval '1 minute' WHERE idem_key = ?", key);

        assertThat(bookingIdempotencyStore.begin(user, key, "h1", Duration.ofHours(1), Duration.ofSeconds(30)))
                .as("crashed first attempt").isInstanceOf(Outcome.Started.class);
        assertThat(bookingIdempotencyStore.begin(user, key, "h2", Duration.ofHours(1), Duration.ofSeconds(30)))
                .isInstanceOf(Outcome.Mismatch.class);
    }

    @Test
    void anExpiredKeyCanBeReused() {
        String key = UUID.randomUUID().toString();
        post(user, key, "A10");
        jdbc.update("UPDATE booking.idempotency_keys SET expires_at = now() - interval '1 second' WHERE idem_key = ?", key);

        ResponseEntity<Map> later = post(user, key, "A11");

        assertThat(later.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bookingIdempotencyStore.purgeExpired(100)).isZero();
    }

    /** The fingerprint the filter computes for POST /api/v1/bookings with this exact body. */
    private static String hashOf(String seat) {
        return sha256("POST /api/v1/bookings\n" + body(seat));
    }

    private static String body(String... seats) {
        return "{\"showId\":\"" + DEMO_SHOW + "\",\"seatCodes\":[" + String.join(",", List.of(seats).stream().map(s -> "\"" + s + "\"").toList()) + "]}";
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private ResponseEntity<Map> post(UUID userId, String key, String... seats) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId.toString());
        if (key != null) headers.set("Idempotency-Key", key);
        return http.postForEntity("/api/v1/bookings", new HttpEntity<>(body(seats), headers), Map.class);
    }
}
