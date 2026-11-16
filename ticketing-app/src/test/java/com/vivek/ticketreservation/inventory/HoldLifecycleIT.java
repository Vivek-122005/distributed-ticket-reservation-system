package com.vivek.ticketreservation.inventory;

import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryTestData;
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

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static com.vivek.ticketreservation.support.InventoryTestData.seatStatus;
import static org.assertj.core.api.Assertions.assertThat;

/** P1.4: get / confirm / release over HTTP (FR-04, FR-06, UC-03, UC-04), including the race and retry cases. */
@IntegrationTest
class HoldLifecycleIT {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void reset() { InventoryTestData.reset(jdbc); }

    @Test
    void confirmBooksTheSeatsAndIsIdempotent() {
        UUID hold = hold("A1", "A2");

        ResponseEntity<Map> first = call(HttpMethod.POST, "/holds/{id}/confirm", hold, user);
        ResponseEntity<Map> again = call(HttpMethod.POST, "/holds/{id}/confirm", hold, user);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("status", "CONFIRMED").containsEntry("totalMinor", 300000);
        assertThat(again.getStatusCode()).as("saga retry is safe").isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("status", "CONFIRMED");
        assertThat(seatStatus(jdbc, "A1")).isEqualTo("BOOKED");
        assertThat(seatStatus(jdbc, "A2")).isEqualTo("BOOKED");
    }

    @Test
    void releaseFreesTheSeatsAndIsIdempotent() {
        UUID hold = hold("A3");

        ResponseEntity<Map> first = call(HttpMethod.DELETE, "/holds/{id}", hold, user);
        ResponseEntity<Map> again = call(HttpMethod.DELETE, "/holds/{id}", hold, user);

        assertThat(first.getBody()).containsEntry("status", "RELEASED");
        assertThat(first.getBody().get("seats")).as("a released hold still reports its seats")
                .isEqualTo(List.of(Map.of("seatCode", "A3", "priceMinor", 150000)));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(seatStatus(jdbc, "A3")).isEqualTo("AVAILABLE");
        assertThat(hold(UUID.randomUUID(), "A3")).as("seat is sellable again").isNotNull();
    }

    @Test
    void confirmedHoldCannotBeReleased() {
        UUID hold = hold("A4");
        call(HttpMethod.POST, "/holds/{id}/confirm", hold, user);

        ResponseEntity<Map> release = call(HttpMethod.DELETE, "/holds/{id}", hold, user);

        assertThat(release.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(release.getBody()).containsEntry("code", "HOLD_ALREADY_CONFIRMED");
        assertThat(seatStatus(jdbc, "A4")).isEqualTo("BOOKED");
    }

    @Test
    void releasedHoldCannotBeConfirmed() {
        UUID hold = hold("A5");
        call(HttpMethod.DELETE, "/holds/{id}", hold, user);

        ResponseEntity<Map> confirm = call(HttpMethod.POST, "/holds/{id}/confirm", hold, user);

        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(confirm.getBody()).containsEntry("code", "ILLEGAL_STATE_TRANSITION");
    }

    @Test
    void confirmAfterExpiryFailsAndChangesNothing() {
        UUID hold = hold("A6");
        expire(hold);

        ResponseEntity<Map> confirm = call(HttpMethod.POST, "/holds/{id}/confirm", hold, user);

        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(confirm.getBody()).containsEntry("code", "HOLD_EXPIRED");
        assertThat(seatStatus(jdbc, "A6")).as("rolled back, never BOOKED").isEqualTo("HELD");
        assertThat(holdStatus(hold)).isEqualTo("HELD");
    }

    @Test
    void confirmFailsIfASeatWasReHeldByAnotherHold() {
        UUID mine = hold("A7", "A8");
        UUID theirs = hold(UUID.randomUUID(), "A9");
        // The race of P1.2: our hold expired an instant after our check and A8 was lazily re-held.
        jdbc.update("UPDATE inventory.show_seats SET hold_id = ? WHERE show_id = ? AND seat_code = 'A8'", theirs, DEMO_SHOW);

        ResponseEntity<Map> confirm = call(HttpMethod.POST, "/holds/{id}/confirm", mine, user);

        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(confirm.getBody()).containsEntry("code", "HOLD_EXPIRED");
        assertThat(seatStatus(jdbc, "A7")).as("no partial booking: A7 is rolled back too").isEqualTo("HELD");
        assertThat(holdStatus(mine)).isEqualTo("HELD");
    }

    @Test
    void releasingAnExpiredHoldRecordsExpiryAndFreesOnlyItsOwnSeats() {
        UUID mine = hold("A10", "A11");
        expire(mine);
        UUID theirs = hold(UUID.randomUUID(), "A11");   // lazy re-hold of one seat

        ResponseEntity<Map> release = call(HttpMethod.DELETE, "/holds/{id}", mine, user);

        assertThat(release.getBody()).containsEntry("status", "EXPIRED");
        assertThat(holdStatus(mine)).isEqualTo("EXPIRED");
        assertThat(seatStatus(jdbc, "A10")).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT hold_id FROM inventory.show_seats WHERE show_id = ? AND seat_code = 'A11'",
                UUID.class, DEMO_SHOW)).as("the other user's seat is untouched").isEqualTo(theirs);
    }

    @Test
    void getReportsLazyExpiry() {
        UUID hold = hold("A12");
        assertThat(call(HttpMethod.GET, "/holds/{id}", hold, user).getBody()).containsEntry("status", "HELD");

        expire(hold);

        assertThat(call(HttpMethod.GET, "/holds/{id}", hold, user).getBody()).containsEntry("status", "EXPIRED");
    }

    @Test
    void anotherUsersHoldIsNotFound() {
        UUID hold = hold("A13");
        UUID stranger = UUID.randomUUID();

        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.DELETE)) {
            ResponseEntity<Map> response = call(method, "/holds/{id}", hold, stranger);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("code", "HOLD_NOT_FOUND");
        }
        assertThat(call(HttpMethod.POST, "/holds/{id}/confirm", hold, stranger).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(seatStatus(jdbc, "A13")).isEqualTo("HELD");
    }

    private UUID hold(String... seats) { return hold(user, seats); }

    private UUID hold(UUID who, String... seats) {
        HttpHeaders headers = headers(who);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> created = http.postForEntity("/api/v1/shows/{showId}/holds",
                new HttpEntity<>(Map.of("seatCodes", List.of(seats)), headers), Map.class, DEMO_SHOW);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("holdId"));
    }

    private ResponseEntity<Map> call(HttpMethod method, String path, UUID holdId, UUID who) {
        return http.exchange("/api/v1" + path, method, new HttpEntity<>(headers(who)), Map.class, holdId);
    }

    private static HttpHeaders headers(UUID who) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", who.toString());
        return headers;
    }

    private void expire(UUID holdId) {
        jdbc.update("UPDATE inventory.holds SET expires_at = now() - interval '1 second' WHERE id = ?", holdId);
        jdbc.update("UPDATE inventory.show_seats SET hold_expires_at = now() - interval '1 second' WHERE hold_id = ?", holdId);
    }

    private String holdStatus(UUID holdId) {
        return jdbc.queryForObject("SELECT status FROM inventory.holds WHERE id = ?", String.class, holdId);
    }
}
