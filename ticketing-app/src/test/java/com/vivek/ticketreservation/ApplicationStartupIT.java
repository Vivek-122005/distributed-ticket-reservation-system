package com.vivek.ticketreservation;

import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;

/** The whole modular monolith starts against real Postgres and Flyway applies every module's migrations. */
@IntegrationTest
class ApplicationStartupIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryApi inventoryApi;

    @Test
    void flywayAppliedAllInventoryMigrations() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class);
        assertThat(applied).containsExactly("1", "2");
    }

    @Test
    void v2CreatesTheShowBasedDemoCatalog() {
        Integer showSeats = jdbc.queryForObject(
                "SELECT count(*) FROM inventory.show_seats WHERE show_id = ?", Integer.class, DEMO_SHOW);
        assertThat(showSeats).isEqualTo(100);
        Integer venueSeats = jdbc.queryForObject("SELECT count(*) FROM inventory.seats", Integer.class);
        assertThat(venueSeats).as("one show seat per physical seat").isEqualTo(showSeats);
        Long priced = jdbc.queryForObject(
                "SELECT count(*) FROM inventory.show_seats WHERE price_minor = 150000", Long.class);
        assertThat(priced).as("price copied from the section").isEqualTo(100L);
    }

    @Test
    void v2RetiresTheLegacyPublicTables() {
        Integer legacy = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('events','holds','seat_inventory')",
                Integer.class);
        assertThat(legacy).isZero();
    }

    @Test
    void publishedInventoryApiIsWiredToThePackagePrivateFacade() {
        assertThat(inventoryApi.getClass().getSimpleName()).startsWith("InventoryFacade");
    }
}
