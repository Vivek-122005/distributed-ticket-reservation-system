package com.vivek.ticketreservation;

import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** The whole modular monolith starts against real Postgres and Flyway applies every module's migrations. */
@IntegrationTest
class ApplicationStartupIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryApi inventoryApi;

    @Test
    void flywayAppliedInventoryMigrationFromModuleFolder() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);
        Integer seats = jdbc.queryForObject("SELECT count(*) FROM seat_inventory", Integer.class);
        assertThat(seats).isEqualTo(100);
    }

    @Test
    void publishedInventoryApiIsWiredToThePackagePrivateFacade() {
        assertThat(inventoryApi.getClass().getSimpleName()).startsWith("InventoryFacade");
    }
}
