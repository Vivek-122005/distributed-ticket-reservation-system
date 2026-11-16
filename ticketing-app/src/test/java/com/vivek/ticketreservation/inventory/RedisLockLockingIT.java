package com.vivek.ticketreservation.inventory;

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

/** Runs the {@link SeatLockingContract} against the redis-lock strategy, plus its fencing and cleanup rules. */
@TestPropertySource(properties = "inventory.locking.strategy=redis-lock")
@Import(RedisContainerConfig.class)
class RedisLockLockingIT extends SeatLockingContract {

    @Autowired StringRedisTemplate redis;

    @Override
    protected void resetStrategyState() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void locksAreReleasedAfterTheTransaction() {
        inventory.hold(new com.vivek.ticketreservation.inventory.api.HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A30", "A31")));

        assertThat(redis.keys("lock:*")).as("locks live only for the transaction").isEmpty();
    }

    @Test
    void lostFenceCounterIsReseededFromPostgres() {
        inventory.hold(new com.vivek.ticketreservation.inventory.api.HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A33")));
        redis.delete("fence:{" + DEMO_SHOW + "}");   // Redis restarted without its data

        assertThat(inventory.hold(new com.vivek.ticketreservation.inventory.api.HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A34"))))
                .as("a fresh counter must not fall behind tokens already written").isNotNull();
    }

    @Test
    void writeWithAStaleFencingTokenIsRejected() {
        // Our counter is at 5, but a newer lock holder (after our lock expired) already wrote with a far higher token.
        redis.opsForValue().set("fence:{" + DEMO_SHOW + "}", "5");
        jdbc.update("UPDATE inventory.show_seats SET lock_fence = 1000000 WHERE show_id = ? AND seat_code = 'A32'", DEMO_SHOW);

        assertThatThrownBy(() -> inventory.hold(new com.vivek.ticketreservation.inventory.api.HoldCommand(DEMO_SHOW, UUID.randomUUID(), List.of("A32"))))
                .isInstanceOf(com.vivek.ticketreservation.inventory.api.SeatUnavailableException.class);
        assertThat(com.vivek.ticketreservation.support.InventoryTestData.seatStatus(jdbc, "A32")).isEqualTo("AVAILABLE");
        jdbc.update("UPDATE inventory.show_seats SET lock_fence = 0 WHERE show_id = ?", DEMO_SHOW);
    }
}
