package com.vivek.ticketreservation.inventory.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Show-seat rules: the readable form of what the SQL claim enforces (P1.1–P1.2). */
class ShowSeatTest {

    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private final UUID holdA = UUID.randomUUID();
    private final UUID holdB = UUID.randomUUID();

    private static ShowSeat available() {
        return new ShowSeat(UUID.randomUUID(), "A1", 150000, SeatStatus.AVAILABLE, null, null);
    }

    @Test
    void availableSeatCanBeHeld() {
        ShowSeat seat = available();
        seat.hold(holdA, NOW.plusSeconds(300), NOW);
        assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.holdId()).isEqualTo(holdA);
    }

    @Test
    void liveHoldBlocksOthersButExpiredHoldDoesNot() {
        ShowSeat seat = available();
        seat.hold(holdA, NOW.plusSeconds(300), NOW);
        assertThatThrownBy(() -> seat.hold(holdB, NOW.plusSeconds(600), NOW)).isInstanceOf(IllegalStateTransitionException.class);

        Instant later = NOW.plusSeconds(301);
        seat.hold(holdB, later.plusSeconds(300), later);
        assertThat(seat.holdId()).as("lazy expiry: re-held by B").isEqualTo(holdB);
    }

    @Test
    void onlyTheOwningHoldCanBookOrRelease() {
        ShowSeat seat = available();
        seat.hold(holdA, NOW.plusSeconds(300), NOW);
        assertThatThrownBy(() -> seat.book(holdB)).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> seat.release(holdB)).as("a late expiry of an old hold must not free a re-held seat")
                .isInstanceOf(IllegalStateTransitionException.class);

        seat.book(holdA);
        assertThat(seat.status()).isEqualTo(SeatStatus.BOOKED);
    }

    @Test
    void releaseClearsTheHold() {
        ShowSeat seat = available();
        seat.hold(holdA, NOW.plusSeconds(300), NOW);
        seat.release(holdA);
        assertThat(seat.status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.holdId()).isNull();
        assertThat(seat.holdExpiresAt()).isNull();
    }

    @Test
    void bookedSeatIsFinal() {
        ShowSeat seat = available();
        seat.hold(holdA, NOW.plusSeconds(300), NOW);
        seat.book(holdA);
        assertThat(seat.isClaimable(NOW.plusSeconds(10_000))).as("BOOKED is never lazily reclaimed").isFalse();
        assertThatThrownBy(() -> seat.release(holdA)).isInstanceOf(IllegalStateTransitionException.class);
    }
}
