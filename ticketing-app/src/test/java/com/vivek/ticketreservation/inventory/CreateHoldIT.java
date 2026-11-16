package com.vivek.ticketreservation.inventory;

import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryTestData;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_EVENT;
import static com.vivek.ticketreservation.support.InventoryTestData.seatStatus;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end hold behaviour over HTTP against real Postgres (FR-03).
 * The heavy multi-threaded concurrency suite is P1.6; this covers the single-request guarantees.
 */
@IntegrationTest
class CreateHoldIT {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetInventory() { InventoryTestData.reset(jdbc); }

    @Test
    void holdsAllRequestedSeats() {
        ResponseEntity<Map> response = hold(UUID.randomUUID(), "A1", "a2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "HELD");
        assertThat(response.getBody().get("seatCodes")).isEqualTo(List.of("A1", "A2"));
        assertThat(seatStatus(jdbc, "A1")).isEqualTo("HELD");
        assertThat(seatStatus(jdbc, "A2")).isEqualTo("HELD");
    }

    @Test
    void conflictingRequestHoldsNothing() {
        hold(UUID.randomUUID(), "A2");

        ResponseEntity<Map> second = hold(UUID.randomUUID(), "A2", "A3");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("code", "SEAT_UNAVAILABLE");
        assertThat(seatStatus(jdbc, "A3")).as("all-or-nothing: A3 must not stay held").isEqualTo("AVAILABLE");
        Integer holds = jdbc.queryForObject("SELECT count(*) FROM holds", Integer.class);
        assertThat(holds).as("the failed hold row is rolled back too").isEqualTo(1);
    }

    @Test
    void duplicateSeatCodesAreRejected() {
        ResponseEntity<Map> response = hold(UUID.randomUUID(), "A5", "a5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "INVALID_REQUEST");
        assertThat(seatStatus(jdbc, "A5")).isEqualTo("AVAILABLE");
    }

    @Test
    void unknownSeatIsUnavailable() {
        ResponseEntity<Map> response = hold(UUID.randomUUID(), "Z999");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private ResponseEntity<Map> hold(UUID userId, String... seatCodes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId.toString());
        return http.postForEntity("/api/v1/events/{eventId}/holds",
                new HttpEntity<>(Map.of("seatCodes", List.of(seatCodes)), headers), Map.class, DEMO_EVENT);
    }
}
