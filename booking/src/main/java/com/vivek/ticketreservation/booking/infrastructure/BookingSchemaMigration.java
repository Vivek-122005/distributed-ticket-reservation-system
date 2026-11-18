package com.vivek.ticketreservation.booking.infrastructure;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Migrates the {@code booking} schema at startup with its <b>own</b> Flyway history table
 * ({@code booking.flyway_schema_history}), separate from inventory's (P3.1).
 *
 * <p>Why not one shared Flyway run over every module's folder: versions would have to be globally
 * unique across modules (booking V1 clashes with inventory V1), and the history would tie the modules
 * together. With one history per schema each module numbers its own migrations and can move to its
 * own database on extraction (P7.8) with its history intact. Deliberately not a {@code Flyway} bean,
 * which would switch off Spring Boot's auto-configured inventory migration.
 */
@Component
class BookingSchemaMigration implements InitializingBean {
    private final DataSource dataSource;

    BookingSchemaMigration(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public void afterPropertiesSet() {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("booking")
                .defaultSchema("booking")
                .createSchemas(true)
                .locations("classpath:db/migration/booking")
                .load()
                .migrate();
    }
}
