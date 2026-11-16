package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.application.port.CatalogReadStore;
import com.vivek.ticketreservation.inventory.application.port.SeatFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Wires the Redis seat cache when {@code inventory.seat-cache.enabled=true} (P2.3). When disabled,
 * nothing here exists and seat maps come straight from Postgres, the configuration used by tests
 * without Redis.
 */
@Configuration
@ConditionalOnProperty(name = "inventory.seat-cache.enabled", havingValue = "true")
class SeatCacheConfig {

    /**
     * Breaker "redis" (P2.6): opens when half of the last 10 calls failed (at least 5 seen), or immediately
     * on timeouts piling up; stays open {@code wait-in-open}, then lets 3 trial calls through. Exported as
     * {@code resilience4j_circuitbreaker_state{name="redis"}} and friends.
     */
    @Bean
    RedisGuard redisGuard(MeterRegistry registry,
                          @Value("${inventory.redis-breaker.wait-in-open:5s}") Duration waitInOpen) {
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(waitInOpen).permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(registry);
        CircuitBreaker breaker = breakers.circuitBreaker("redis");
        return new RedisGuard(breaker, registry);
    }

    @Bean SeatStateCache seatStateCache(StringRedisTemplate redis) { return new SeatStateCache(redis); }

    @Bean SeatSnapshots seatSnapshots(NamedParameterJdbcTemplate jdbc) { return new SeatSnapshots(jdbc); }

    @Bean(destroyMethod = "shutdown")
    SeatCacheUpdater seatCacheUpdater(SeatStateCache cache, RedisGuard guard, MeterRegistry registry,
                                      @Value("${inventory.seat-cache.update-queue:10000}") int queueCapacity) {
        return new SeatCacheUpdater(cache, guard, registry, queueCapacity);
    }

    /** Fast-reject filter in front of hold creation (P2.4); off with {@code inventory.fast-path.enabled=false}. */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "inventory.fast-path.enabled", havingValue = "true", matchIfMissing = true)
    SeatFilter redisSeatFilter(StringRedisTemplate redis, RedisGuard guard, MeterRegistry registry) {
        return new RedisSeatFilter(redis, guard, registry);
    }

    @Bean
    SeatCacheReconciler seatCacheReconciler(SeatStateCache cache, SeatSnapshots snapshots, StringRedisTemplate redis,
                                            NamedParameterJdbcTemplate jdbc, MeterRegistry registry) {
        return new SeatCacheReconciler(cache, snapshots, redis, jdbc, registry);
    }

    @Bean
    @ConditionalOnProperty(name = "inventory.reconcile.enabled", havingValue = "true", matchIfMissing = true)
    ScheduledSeatCacheReconciliation scheduledSeatCacheReconciliation(SeatCacheReconciler reconciler, RedisGuard guard) {
        return new ScheduledSeatCacheReconciliation(reconciler, guard);
    }

    /** The decorated store becomes the one the read side uses; the JDBC store stays inside as fallback. */
    @Bean
    @Primary
    CatalogReadStore cachedCatalogReadStore(@Qualifier("jdbcCatalogReadStore") CatalogReadStore database,
                                            SeatStateCache cache, SeatSnapshots snapshots, RedisGuard guard,
                                            MeterRegistry registry) {
        return new CachedCatalogReadStore(database, cache, snapshots, guard, registry);
    }
}
