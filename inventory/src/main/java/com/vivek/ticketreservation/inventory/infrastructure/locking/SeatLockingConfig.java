package com.vivek.ticketreservation.inventory.infrastructure.locking;

import com.vivek.ticketreservation.inventory.application.port.SeatLockingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Creates the one {@link SeatLockingStrategy} selected by {@code inventory.locking.strategy}.
 *
 * <p>Patterns: Strategy (docs/lld/DESIGN_PATTERNS.md#7) chosen by a factory method. OCP: a new
 * strategy is a new class plus one {@code case}; {@code CreateHoldUseCase} never changes. The
 * implementations are package-private, so nothing can depend on a concrete strategy.
 */
@Configuration
@EnableConfigurationProperties(LockingProperties.class)
class SeatLockingConfig {
    private static final Logger log = LoggerFactory.getLogger(SeatLockingConfig.class);

    @Bean
    SeatLockingStrategy seatLockingStrategy(LockingProperties properties, NamedParameterJdbcTemplate jdbc,
                                            ObjectProvider<StringRedisTemplate> redis) {
        SeatRows rows = new SeatRows(jdbc);
        SeatLockingStrategy strategy = switch (properties.strategy()) {
            case CONDITIONAL_UPDATE -> new ConditionalUpdateStrategy(jdbc);
            case PESSIMISTIC -> new PessimisticLockStrategy(rows);
            case OPTIMISTIC -> new OptimisticLockStrategy(rows);
            case REDIS_LOCK -> new RedisLockStrategy(rows, redis.getObject());
            case REDIS_LUA -> new RedisLuaStrategy(new ConditionalUpdateStrategy(jdbc), redis.getObject());
        };
        log.info("Seat locking strategy: {} ({})", properties.strategy(), strategy.getClass().getSimpleName());
        return strategy;
    }
}
