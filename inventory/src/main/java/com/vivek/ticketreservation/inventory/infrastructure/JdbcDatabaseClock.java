package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.application.port.DatabaseClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** {@link DatabaseClock} via {@code SELECT now()}: inside a transaction, the same instant SQL uses. */
@Component
class JdbcDatabaseClock implements DatabaseClock {
    private final JdbcTemplate jdbc;

    JdbcDatabaseClock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Instant now() { return jdbc.queryForObject("SELECT now()", Instant.class); }
}
