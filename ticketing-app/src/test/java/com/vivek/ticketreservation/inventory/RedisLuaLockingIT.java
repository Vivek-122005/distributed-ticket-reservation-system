package com.vivek.ticketreservation.inventory;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.support.RedisContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.vivek.ticketreservation.support.InventoryTestData.DEMO_SHOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the {@link SeatLockingContract} against the redis-lua strategy, plus its filter behaviour. */
@TestPropertySource(properties = "inventory.locking.strategy=redis-lua")
@Import(RedisContainerConfig.class)
class RedisLuaLockingIT extends SeatLockingContract {

    @Autowired StringRedisTemplate redis;

    @Override
    protected void resetStrategyState() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    /** Redis is a cache of seat state: a direct database edit must be mirrored, which P2.3–P2.5 automate. */
    @Override
    protected void afterManualExpiry(HoldResult hold) {
        hold.seats().forEach(seat -> redis.opsForHash().delete("seats:{" + DEMO_SHOW + "}", seat.seatCode()));
    }

    @Test
    void failedDatabaseClaimRemovesTheRedisMarks() {
        jdbc.update("UPDATE inventory.show_seats SET status = 'BOOKED', hold_id = ?, held_by_user_id = ?, hold_expires_at = now() "
                + "WHERE show_id = ? AND seat_code = 'A40'", seedHold(), UUID.randomUUID(), DEMO_SHOW);   // Redis does not know

        assertThatThrownBy(() -> inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A40", "A41"))))
                .isInstanceOf(SeatUnavailableException.class);

        assertThat(redis.opsForHash().keys("seats:{" + DEMO_SHOW + "}")).as("rollback cleans our marks").isEmpty();
        assertThat(inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A41")))).isNotNull();
    }

    @Test
    void liveMarkRejectsWithoutTouchingPostgres() {
        inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A42")));
        jdbc.update("UPDATE inventory.show_seats SET status = 'AVAILABLE', hold_id = NULL, held_by_user_id = NULL, hold_expires_at = NULL "
                + "WHERE show_id = ? AND seat_code = 'A42'", DEMO_SHOW);   // database says free, Redis still says held

        assertThatThrownBy(() -> inventory.hold(new HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A42"))))
                .as("filter rejects first; a false reject, never an oversell").isInstanceOf(SeatUnavailableException.class);
    }

    private UUID seedHold() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory.holds (id, show_id, user_id, status, expires_at, seat_codes) "
                + "VALUES (?, ?, ?, 'CONFIRMED', now(), ARRAY['A40'])", id, DEMO_SHOW, UUID.randomUUID());
        return id;
    }
}
