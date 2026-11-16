package com.vivek.ticketreservation.inventory;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.InventoryApi;
import com.vivek.ticketreservation.inventory.application.ExpireHoldsJob;
import com.vivek.ticketreservation.inventory.domain.event.HoldExpired;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.InventoryInvariants;
import com.vivek.ticketreservation.support.InventoryTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static com.vivek.ticketreservation.support.InventoryTestData.seatStatus;
import static org.assertj.core.api.Assertions.assertThat;

/** P2.1: the expiry worker (FR-04, UC-07, invariant 4). */
@IntegrationTest
@Import(ExpireHoldsJobIT.CapturedEvents.class)
class ExpireHoldsJobIT {

    @Autowired ExpireHoldsJob job;
    @Autowired InventoryApi inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapturedEvents captured;

    @BeforeEach
    void reset() {
        InventoryTestData.reset(jdbc);
        captured.events.clear();
    }

    @Test
    void expiresDueHoldsReleasesTheirSeatsAndPublishesAfterCommit() {
        HoldResult due = hold("A1", "A2");
        HoldResult live = hold("A3");
        expire(due);

        int expired = job.runBatch(500);

        assertThat(expired).isEqualTo(1);
        assertThat(holdStatus(due.holdId())).isEqualTo("EXPIRED");
        assertThat(holdStatus(live.holdId())).isEqualTo("HELD");
        assertThat(seatStatus(jdbc, "A1")).isEqualTo("AVAILABLE");
        assertThat(seatStatus(jdbc, "A2")).isEqualTo("AVAILABLE");
        assertThat(seatStatus(jdbc, "A3")).isEqualTo("HELD");
        assertThat(captured.events).singleElement().satisfies(e -> {
            assertThat(e.holdId()).isEqualTo(due.holdId());
            assertThat(e.releasedSeatCodes()).containsExactly("A1", "A2");
        });
        InventoryInvariants.assertHold(jdbc);
    }

    @Test
    void neverReleasesASeatThatWasLazilyReHeldBySomeoneElse() {
        HoldResult old = hold("A4", "A5");
        expire(old);
        HoldResult newer = hold("A5");   // lazy re-hold before the worker ran

        job.runBatch(500);

        assertThat(seatStatus(jdbc, "A4")).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT hold_id FROM inventory.show_seats WHERE show_id = ? AND seat_code = 'A5'",
                UUID.class, DEMO_SHOW)).isEqualTo(newer.holdId());
        assertThat(captured.events).singleElement().satisfies(e -> assertThat(e.releasedSeatCodes()).containsExactly("A4"));
        InventoryInvariants.assertHold(jdbc);
    }

    @Test
    void confirmedAndReleasedHoldsAreLeftAlone() {
        UUID user = UUID.randomUUID();
        HoldResult confirmed = inventory.hold(new HoldCommand(DEMO_SHOW, user, List.of("A6")));
        inventory.confirm(confirmed.holdId(), user);
        HoldResult released = inventory.hold(new HoldCommand(DEMO_SHOW, user, List.of("A7")));
        inventory.release(released.holdId(), user);
        expire(confirmed);
        expire(released);

        assertThat(job.runBatch(500)).isZero();
        assertThat(seatStatus(jdbc, "A6")).isEqualTo("BOOKED");
        assertThat(captured.events).isEmpty();
    }

    @Test
    void worksInBatchesOldestFirst() {
        for (int n = 10; n < 20; n++) expire(hold("A" + n));

        assertThat(job.runBatch(4)).isEqualTo(4);
        assertThat(job.runBatch(4)).isEqualTo(4);
        assertThat(job.runBatch(4)).isEqualTo(2);
        assertThat(job.runBatch(4)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.holds WHERE status = 'EXPIRED'", Integer.class)).isEqualTo(10);
    }

    private HoldResult hold(String... seats) {
        return inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of(seats)));
    }

    private void expire(HoldResult hold) {
        jdbc.update("UPDATE inventory.holds SET expires_at = now() - interval '1 second' WHERE id = ?", hold.holdId());
        jdbc.update("UPDATE inventory.show_seats SET hold_expires_at = now() - interval '1 second' WHERE hold_id = ?", hold.holdId());
    }

    private String holdStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM inventory.holds WHERE id = ?", String.class, id);
    }

    /** Records committed HoldExpired events, as any downstream listener would see them. */
    @TestConfiguration
    static class CapturedEvents {
        final List<HoldExpired> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void on(HoldExpired event) { events.add(event); }
    }
}
