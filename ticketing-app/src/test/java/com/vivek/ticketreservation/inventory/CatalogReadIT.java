package com.vivek.ticketreservation.inventory;

import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_EVENT;
import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;

/** P1.5: catalog and seat-map reads (FR-01, FR-02, UC-01). */
@IntegrationTest
class CatalogReadIT {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        InventoryTestData.reset(jdbc);
        jdbc.update("DELETE FROM inventory.events WHERE event_code LIKE 'it-%'");
    }

    @Test
    void listsOnlyPublishedEvents() {
        jdbc.update("INSERT INTO inventory.events (id, event_code, name, status) VALUES (?, 'it-draft', 'Draft', 'DRAFT')", UUID.randomUUID());

        ResponseEntity<Map> page = http.getForEntity("/api/v1/events?page=0&size=10", Map.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).containsEntry("page", 0).containsEntry("size", 10).containsEntry("totalItems", 1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.getBody().get("items");
        assertThat(items).extracting(e -> e.get("eventCode")).containsExactly("demo-concert");
    }

    @Test
    void rejectsOversizedPages() {
        ResponseEntity<Map> page = http.getForEntity("/api/v1/events?size=101", Map.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(page.getBody()).containsEntry("code", "INVALID_REQUEST");
    }

    @Test
    void listsShowsWithLiveAvailability() {
        hold("A1", "A2");

        ResponseEntity<List> shows = http.getForEntity("/api/v1/events/{id}/shows", List.class, DEMO_EVENT);

        assertThat(shows.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> show = (Map<String, Object>) shows.getBody().get(0);
        assertThat(show).containsEntry("showId", DEMO_SHOW.toString()).containsEntry("venueName", "Demo Arena")
                .containsEntry("status", "ON_SALE").containsEntry("availableSeats", 98);
    }

    @Test
    void unpublishedOrUnknownEventIsNotFound() {
        UUID draft = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory.events (id, event_code, name, status) VALUES (?, 'it-hidden', 'Hidden', 'DRAFT')", draft);

        for (UUID id : List.of(draft, UUID.randomUUID())) {
            ResponseEntity<Map> response = http.getForEntity("/api/v1/events/{id}/shows", Map.class, id);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("code", "EVENT_NOT_FOUND");
        }
    }

    @Test
    void seatMapShowsStatusPriceAndLayout() {
        hold("A1");
        UUID expired = hold("A2");
        jdbc.update("UPDATE inventory.show_seats SET hold_expires_at = now() - interval '1 second' WHERE hold_id = ?", expired);

        ResponseEntity<Map> map = http.getForEntity("/api/v1/shows/{id}/seats", Map.class, DEMO_SHOW);

        assertThat(map.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(map.getBody()).containsKey("generatedAt");
        List<Map<String, Object>> seats = (List<Map<String, Object>>) map.getBody().get("seats");
        assertThat(seats).hasSize(100);
        assertThat(seats.get(0)).containsEntry("seatCode", "A1").containsEntry("section", "A")
                .containsEntry("row", 1).containsEntry("col", 1).containsEntry("status", "HELD").containsEntry("priceMinor", 150000);
        assertThat(seats.get(1)).as("expired hold is shown as available (lazy expiry)").containsEntry("status", "AVAILABLE");
        assertThat(seats.get(99)).containsEntry("seatCode", "A100").containsEntry("row", 10).containsEntry("col", 10);
    }

    @Test
    void seatMapFiltersBySection() {
        ResponseEntity<Map> none = http.getForEntity("/api/v1/shows/{id}/seats?section=vip", Map.class, DEMO_SHOW);
        ResponseEntity<Map> a = http.getForEntity("/api/v1/shows/{id}/seats?section=a", Map.class, DEMO_SHOW);

        assertThat((List<?>) none.getBody().get("seats")).isEmpty();
        assertThat((List<?>) a.getBody().get("seats")).hasSize(100);
    }

    @Test
    void unknownShowSeatMapIsNotFound() {
        ResponseEntity<Map> response = http.getForEntity("/api/v1/shows/{id}/seats", Map.class, UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "SHOW_NOT_FOUND");
    }

    @Test
    void malformedIdIsABadRequestInTheStandardShape() {
        ResponseEntity<Map> response = http.getForEntity("/api/v1/shows/not-a-uuid/seats", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "INVALID_REQUEST").containsKey("timestamp");
    }

    private UUID hold(String... seats) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", UUID.randomUUID().toString());
        ResponseEntity<Map> created = http.postForEntity("/api/v1/shows/{id}/holds",
                new org.springframework.http.HttpEntity<>(Map.of("seatCodes", List.of(seats)), headers), Map.class, DEMO_SHOW);
        return UUID.fromString((String) created.getBody().get("holdId"));
    }
}
