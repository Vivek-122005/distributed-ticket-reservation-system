package com.vivek.ticketreservation.support;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;

/**
 * Test-data builder for {@code POST /api/v1/bookings}: sensible defaults (demo show, seat A1, a new
 * user and a new key), so each test states only what matters to it.
 *
 * <p>Pattern: Builder, see docs/lld/DESIGN_PATTERNS.md#16-builder. Immutable: every {@code with…}
 * returns a copy, so one base request can be reused across racing threads safely.
 */
public final class BookingRequestBuilder {
    private final UUID user;
    private final UUID show;
    private final List<String> seats;
    private final String key;

    private BookingRequestBuilder(UUID user, UUID show, List<String> seats, String key) {
        this.user = user; this.show = show; this.seats = List.copyOf(seats); this.key = key;
    }

    public static BookingRequestBuilder aBooking() {
        return new BookingRequestBuilder(UUID.randomUUID(), DEMO_SHOW, List.of("A1"), UUID.randomUUID().toString());
    }

    public BookingRequestBuilder byUser(UUID user) { return new BookingRequestBuilder(user, show, seats, key); }
    public BookingRequestBuilder forShow(UUID show) { return new BookingRequestBuilder(user, show, seats, key); }
    public BookingRequestBuilder seats(String... seats) { return new BookingRequestBuilder(user, show, List.of(seats), key); }
    public BookingRequestBuilder withKey(String key) { return new BookingRequestBuilder(user, show, seats, key); }
    public BookingRequestBuilder withNewKey() { return withKey(UUID.randomUUID().toString()); }

    public UUID user() { return user; }
    public String key() { return key; }

    public String json() {
        return "{\"showId\":\"" + show + "\",\"seatCodes\":[" + String.join(",", seats.stream().map(s -> "\"" + s + "\"").toList()) + "]}";
    }

    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", user.toString());
        if (key != null) headers.set("Idempotency-Key", key);
        return headers;
    }

    @SuppressWarnings("rawtypes")
    public ResponseEntity<Map> post(TestRestTemplate http) {
        return http.postForEntity("/api/v1/bookings", new HttpEntity<>(json(), headers()), Map.class);
    }
}
