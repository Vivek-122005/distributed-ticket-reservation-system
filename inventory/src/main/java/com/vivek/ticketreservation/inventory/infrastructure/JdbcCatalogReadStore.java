package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.api.CatalogQuery.EventView;
import com.vivek.ticketreservation.inventory.api.CatalogQuery.ShowView;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery.SeatMapView;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery.SeatView;
import com.vivek.ticketreservation.inventory.application.port.CatalogReadStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CatalogReadStore} over Postgres. Lazy expiry (P1.2) applies to reads too: a HELD seat whose
 * hold has expired is reported AVAILABLE, because a hold request for it would succeed.
 */
@Component
class JdbcCatalogReadStore implements CatalogReadStore {

    /** Same claimability rule as ConditionalUpdateStrategy, as a SQL expression. */
    private static final String EFFECTIVE_STATUS =
            "CASE WHEN ss.status = 'HELD' AND ss.hold_expires_at < now() THEN 'AVAILABLE' ELSE ss.status END";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCatalogReadStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<EventView> publishedEvents(int offset, int limit) {
        return jdbc.query("""
                SELECT id, event_code, name, description, status FROM inventory.events
                 WHERE status = 'PUBLISHED'
                 ORDER BY created_at DESC, id
                 LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource("limit", limit).addValue("offset", offset),
                (rs, i) -> new EventView(rs.getObject("id", UUID.class), rs.getString("event_code"),
                        rs.getString("name"), rs.getString("description"), rs.getString("status")));
    }

    @Override
    public long countPublishedEvents() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM inventory.events WHERE status = 'PUBLISHED'",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<List<ShowView>> showsOfPublishedEvent(UUID eventId) {
        MapSqlParameterSource params = new MapSqlParameterSource("eventId", eventId);
        Integer published = jdbc.queryForObject(
                "SELECT count(*) FROM inventory.events WHERE id = :eventId AND status = 'PUBLISHED'", params, Integer.class);
        if (published == null || published == 0) return Optional.empty();
        return Optional.of(jdbc.query("""
                SELECT sh.id, sh.event_id, sh.venue_id, v.name AS venue_name, sh.starts_at, sh.sale_opens_at, sh.status,
                       (SELECT count(*) FROM inventory.show_seats ss
                         WHERE ss.show_id = sh.id AND %s = 'AVAILABLE') AS available
                  FROM inventory.shows sh
                  JOIN inventory.venues v ON v.id = sh.venue_id
                 WHERE sh.event_id = :eventId
                 ORDER BY sh.starts_at, sh.id
                """.formatted(EFFECTIVE_STATUS), params,
                (rs, i) -> new ShowView(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                        rs.getObject("venue_id", UUID.class), rs.getString("venue_name"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("sale_opens_at").toInstant(),
                        rs.getString("status"), rs.getInt("available"))));
    }

    @Override
    public Optional<SeatMapView> seatMap(UUID showId, Optional<String> section) {
        MapSqlParameterSource params = new MapSqlParameterSource("showId", showId)
                .addValue("section", section.orElse(null));
        Instant generatedAt = jdbc.query("SELECT now() AS now FROM inventory.shows WHERE id = :showId", params,
                (rs, i) -> rs.getTimestamp("now").toInstant()).stream().findFirst().orElse(null);
        if (generatedAt == null) return Optional.empty();
        List<SeatView> seats = jdbc.query("""
                SELECT ss.seat_id, ss.seat_code, ss.section_code, s.row_no, s.col_no, %s AS status, ss.price_minor
                  FROM inventory.show_seats ss
                  JOIN inventory.seats s ON s.id = ss.seat_id
                 WHERE ss.show_id = :showId
                   AND (CAST(:section AS VARCHAR) IS NULL OR ss.section_code = :section)
                 ORDER BY ss.section_code, s.row_no, s.col_no
                """.formatted(EFFECTIVE_STATUS), params,
                (rs, i) -> new SeatView(rs.getObject("seat_id", UUID.class), rs.getString("seat_code"),
                        rs.getString("section_code"), rs.getInt("row_no"), rs.getInt("col_no"),
                        rs.getString("status"), rs.getLong("price_minor")));
        return Optional.of(new SeatMapView(showId, generatedAt, seats));
    }
}
