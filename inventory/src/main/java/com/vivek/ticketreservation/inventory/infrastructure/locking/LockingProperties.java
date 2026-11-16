package com.vivek.ticketreservation.inventory.infrastructure.locking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code inventory.locking.strategy}: which {@code SeatLockingStrategy} the module uses (P1.7).
 * An unknown value fails startup with a binding error naming the allowed values.
 */
@ConfigurationProperties(prefix = "inventory.locking")
public record LockingProperties(@DefaultValue("conditional-update") Strategy strategy) {

    public enum Strategy { CONDITIONAL_UPDATE, PESSIMISTIC, OPTIMISTIC, REDIS_LOCK, REDIS_LUA }
}
