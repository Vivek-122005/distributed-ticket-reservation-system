package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.application.port.ShowStore;
import com.vivek.ticketreservation.inventory.domain.Show;
import com.vivek.ticketreservation.inventory.domain.ShowStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** {@link ShowStore} over {@code inventory.shows} with plain JDBC. */
@Component
class JdbcShowStore implements ShowStore {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcShowStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Show> find(UUID showId) {
        return jdbc.query("SELECT id, status, currency FROM inventory.shows WHERE id = :id",
                new MapSqlParameterSource("id", showId),
                (rs, i) -> new Show(rs.getObject("id", UUID.class), ShowStatus.valueOf(rs.getString("status")), rs.getString("currency")))
                .stream().findFirst();
    }
}
