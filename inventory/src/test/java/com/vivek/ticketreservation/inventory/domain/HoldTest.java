package com.vivek.ticketreservation.inventory.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hold state machine (plan §4.3), without Spring or a database. */
class HoldTest {

    private static final Instant EXPIRES = Instant.parse("2026-11-16T10:05:00Z");
    private static final Instant BEFORE = EXPIRES.minusSeconds(60);
    private static final Instant AFTER = EXPIRES.plusSeconds(1);

    private static Hold hold(HoldStatus status) {
        return new Hold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status, EXPIRES, List.of("A1"));
    }

    @Test
    void confirmWhileLiveMovesToConfirmed() {
        Hold hold = hold(HoldStatus.HELD);
        assertThat(hold.confirm(BEFORE)).isTrue();
        assertThat(hold.status()).isEqualTo(HoldStatus.CONFIRMED);
    }

    @Test
    void confirmIsIdempotent() {
        Hold hold = hold(HoldStatus.CONFIRMED);
        assertThat(hold.confirm(AFTER)).as("repeat confirm, even later, is a no-op").isFalse();
        assertThat(hold.status()).isEqualTo(HoldStatus.CONFIRMED);
    }

    @Test
    void confirmAfterExpiryFails() {
        Hold hold = hold(HoldStatus.HELD);
        assertThatThrownBy(() -> hold.confirm(AFTER)).isInstanceOf(HoldExpiredException.class);
        assertThat(hold.status()).isEqualTo(HoldStatus.HELD);
    }

    @Test
    void expiryInstantItselfIsStillLive() {
        Hold hold = hold(HoldStatus.HELD);
        assertThat(hold.isExpired(EXPIRES)).as("same rule as SQL: hold_expires_at < now()").isFalse();
        assertThat(hold.confirm(EXPIRES)).isTrue();
    }

    @Test
    void releasedOrExpiredHoldCannotBeConfirmed() {
        assertThatThrownBy(() -> hold(HoldStatus.RELEASED).confirm(BEFORE)).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> hold(HoldStatus.EXPIRED).confirm(BEFORE)).isInstanceOf(HoldExpiredException.class);
    }

    @Test
    void releaseIsIdempotentButNeverUndoesAConfirmation() {
        Hold held = hold(HoldStatus.HELD);
        assertThat(held.release()).isTrue();
        assertThat(held.release()).isFalse();
        assertThat(hold(HoldStatus.EXPIRED).release()).isFalse();
        assertThatThrownBy(() -> hold(HoldStatus.CONFIRMED).release()).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void expireOnlyWhenDueAndHeld() {
        assertThat(hold(HoldStatus.HELD).expire(BEFORE)).isFalse();
        Hold due = hold(HoldStatus.HELD);
        assertThat(due.expire(AFTER)).isTrue();
        assertThat(due.status()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(hold(HoldStatus.CONFIRMED).expire(AFTER)).as("a paid hold never expires").isFalse();
    }

    @Test
    void effectiveStatusReportsLazyExpiry() {
        assertThat(hold(HoldStatus.HELD).effectiveStatus(BEFORE)).isEqualTo(HoldStatus.HELD);
        assertThat(hold(HoldStatus.HELD).effectiveStatus(AFTER)).isEqualTo(HoldStatus.EXPIRED);
        assertThat(hold(HoldStatus.CONFIRMED).effectiveStatus(AFTER)).isEqualTo(HoldStatus.CONFIRMED);
    }

    @Test
    void ownership() {
        UUID user = UUID.randomUUID();
        Hold hold = new Hold(UUID.randomUUID(), UUID.randomUUID(), user, HoldStatus.HELD, EXPIRES, List.of("A1"));
        assertThat(hold.belongsTo(user)).isTrue();
        assertThat(hold.belongsTo(UUID.randomUUID())).isFalse();
    }
}
