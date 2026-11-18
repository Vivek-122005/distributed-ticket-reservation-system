package com.vivek.ticketreservation.payment.infrastructure;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Migrates the {@code payment} schema with its own history table, like booking (P3.1). */
@Component
class PaymentSchemaMigration implements InitializingBean {
    private final DataSource dataSource;

    PaymentSchemaMigration(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public void afterPropertiesSet() {
        Flyway.configure().dataSource(dataSource).schemas("payment").defaultSchema("payment").createSchemas(true)
                .locations("classpath:db/migration/payment").load().migrate();
    }
}
