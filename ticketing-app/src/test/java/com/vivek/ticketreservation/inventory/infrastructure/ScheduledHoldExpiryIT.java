package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.inventory.application.ExpireHoldsJob;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryTestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;

/** P2.1: one hold the database refuses to update must not stop the others from expiring. */
@IntegrationTest
class ScheduledHoldExpiryIT {

    @Autowired ExpireHoldsJob job;
    @Autowired InventoryApi inventory;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() { InventoryTestData.reset(jdbc); }

    @AfterEach
    void dropPoison() {
        jdbc.execute("DROP TRIGGER IF EXISTS poison ON inventory.holds");
        jdbc.execute("DROP FUNCTION IF EXISTS inventory.poison()");
    }

    @Test
    void poisonHoldIsSkippedAndCountedWhileTheRestExpire() {
        HoldResult poison = expired("A60");
        HoldResult good1 = expired("A61");
        HoldResult good2 = expired("A62");
        // Make the oldest hold impossible to update, like the pre-V4 rows were.
        jdbc.update("UPDATE inventory.holds SET expires_at = expires_at - interval '1 minute' WHERE id = ?", poison.holdId());
        jdbc.execute("CREATE FUNCTION inventory.poison() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.id = '" + poison.holdId() + "' THEN RAISE EXCEPTION 'poison row'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER poison BEFORE UPDATE ON inventory.holds FOR EACH ROW EXECUTE FUNCTION inventory.poison()");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ScheduledHoldExpiry worker = new ScheduledHoldExpiry(job, registry, 500, 20);

        worker.tick();

        assertThat(status(good1)).isEqualTo("EXPIRED");
        assertThat(status(good2)).isEqualTo("EXPIRED");
        assertThat(status(poison)).isEqualTo("HELD");
        assertThat(registry.counter("hold.expiry.failures").count()).isEqualTo(1);
    }

    private HoldResult expired(String seat) {
        HoldResult hold = inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of(seat)));
        jdbc.update("UPDATE inventory.holds SET expires_at = now() - interval '1 second' WHERE id = ?", hold.holdId());
        jdbc.update("UPDATE inventory.show_seats SET hold_expires_at = now() - interval '1 second' WHERE hold_id = ?", hold.holdId());
        return hold;
    }

    private String status(HoldResult hold) {
        return jdbc.queryForObject("SELECT status FROM inventory.holds WHERE id = ?", String.class, hold.holdId());
    }
}
