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

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static com.vivek.ticketreservation.support.InventoryTestData.seatStatus;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end hold behaviour over HTTP against real Postgres (FR-03, BR-03).
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
        assertThat(response.getBody()).containsEntry("status", "HELD").containsEntry("showId", DEMO_SHOW.toString());
        assertThat(response.getBody().get("seats")).isEqualTo(List.of(
                Map.of("seatCode", "A1", "priceMinor", 150000),
                Map.of("seatCode", "A2", "priceMinor", 150000)));
        assertThat(response.getBody()).containsEntry("totalMinor", 300000).containsEntry("currency", "INR");
        assertThat(seatStatus(jdbc, "A1")).isEqualTo("HELD");
        assertThat(seatStatus(jdbc, "A2")).isEqualTo("HELD");
    }

    @Test
    void conflictingRequestHoldsNothing() {
        hold(UUID.randomUUID(), "A2");

        ResponseEntity<Map> second = hold(UUID.randomUUID(), "A2", "A3");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("code", "SEAT_UNAVAILABLE");
        assertThat(second.getBody()).containsEntry("retryable", false).containsKey("timestamp");
        assertThat(seatStatus(jdbc, "A3")).as("all-or-nothing: A3 must not stay held").isEqualTo("AVAILABLE");
        Integer holds = jdbc.queryForObject("SELECT count(*) FROM inventory.holds", Integer.class);
        assertThat(holds).as("the failed hold row is rolled back too").isEqualTo(1);
    }

    @Test
    void holdUsesThePriceFrozenOnTheShowSeat() {
        jdbc.update("UPDATE inventory.show_seats SET price_minor = 99900 WHERE show_id = ? AND seat_code = 'A9'", DEMO_SHOW);

        ResponseEntity<Map> response = hold(UUID.randomUUID(), "A9", "A10");

        assertThat(response.getBody()).containsEntry("totalMinor", 249900);
        jdbc.update("UPDATE inventory.show_seats SET price_minor = 150000 WHERE show_id = ?", DEMO_SHOW);
    }

    @Test
    void claimIsOneStatementForAllSeats() {
        ResponseEntity<Map> response = hold(UUID.randomUUID(), "A20", "A21", "A22", "A23", "A24", "A25", "A26", "A27");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Integer held = jdbc.queryForObject(
                "SELECT count(*) FROM inventory.show_seats WHERE hold_id = ?::uuid AND status = 'HELD'",
                Integer.class, response.getBody().get("holdId"));
        assertThat(held).isEqualTo(8);
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

    @Test
    void unknownShowIsNotFound() {
        ResponseEntity<Map> response = hold(UUID.randomUUID(), UUID.randomUUID(), "A1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "SHOW_NOT_FOUND");
    }

    @Test
    void showNotOnSaleHoldsNothing() {
        InventoryTestData.setShowStatus(jdbc, "SCHEDULED");

        ResponseEntity<Map> response = hold(UUID.randomUUID(), "A1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "SHOW_NOT_ON_SALE");
        assertThat(seatStatus(jdbc, "A1")).isEqualTo("AVAILABLE");
    }

    @Test
    void storedHoldStatusMatchesTheApiName() {
        hold(UUID.randomUUID(), "A7");

        String stored = jdbc.queryForObject("SELECT status FROM inventory.holds", String.class);
        assertThat(stored).as("no ACTIVE/HELD split (SOLID.md #5)").isEqualTo("HELD");
    }

    private ResponseEntity<Map> hold(UUID userId, String... seatCodes) {
        return hold(DEMO_SHOW, userId, seatCodes);
    }

    private ResponseEntity<Map> hold(UUID showId, UUID userId, String... seatCodes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId.toString());
        return http.postForEntity("/api/v1/shows/{showId}/holds",
                new HttpEntity<>(Map.of("seatCodes", List.of(seatCodes)), headers), Map.class, showId);
    }
}
