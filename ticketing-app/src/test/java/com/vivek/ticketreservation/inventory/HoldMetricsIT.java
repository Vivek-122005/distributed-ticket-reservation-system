package com.vivek.ticketreservation.inventory;

import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.9: hold metrics reach /actuator/prometheus with the names the dashboards (P8) will query.
 * {@code @AutoConfigureObservability}: Spring Boot disables metrics export in tests unless asked.
 */
@IntegrationTest
@AutoConfigureObservability
class HoldMetricsIT {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() { InventoryTestData.reset(jdbc); }

    @Test
    void holdOutcomesConflictsLatencyAndTransitionsAreExported() {
        double heldBefore = value(scrape(), "seat_hold_attempts_total\\{outcome=\"HELD\",strategy=\"conditional-update\"\\}");
        double conflictsBefore = value(scrape(), "seat_hold_conflicts_total\\{strategy=\"conditional-update\"\\}");

        UUID user = UUID.randomUUID();
        String holdId = hold(user, "A90");
        hold(UUID.randomUUID(), "A90");   // conflict
        http.exchange("/api/v1/holds/{id}/confirm", HttpMethod.POST, new HttpEntity<>(headers(user)), Map.class, holdId);

        String metrics = scrape();
        assertThat(value(metrics, "seat_hold_attempts_total\\{outcome=\"HELD\",strategy=\"conditional-update\"\\}")).isEqualTo(heldBefore + 1);
        assertThat(value(metrics, "seat_hold_conflicts_total\\{strategy=\"conditional-update\"\\}")).isEqualTo(conflictsBefore + 1);
        assertThat(metrics).contains("hold_latency_seconds_bucket{outcome=\"HELD\",strategy=\"conditional-update\",le=\"0.25\"}");
        assertThat(metrics).containsPattern("hold_transitions_total\\{operation=\"confirm\",outcome=\"CONFIRMED\"\\} [1-9]");
    }

    private String hold(UUID user, String seat) {
        HttpHeaders headers = headers(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> body = http.postForEntity("/api/v1/shows/{id}/holds", new HttpEntity<>(Map.of("seatCodes", List.of(seat)), headers),
                Map.class, DEMO_SHOW).getBody();
        return (String) body.get("holdId");
    }

    private static HttpHeaders headers(UUID user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user.toString());
        return headers;
    }

    private String scrape() { return http.getForObject("/actuator/prometheus", String.class); }

    private static double value(String metrics, String series) {
        Matcher m = Pattern.compile("^" + series + " ([0-9.eE+-]+)$", Pattern.MULTILINE).matcher(metrics);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }
}
