package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.application.port.CatalogReadStore;
import com.vivek.ticketreservation.inventory.application.port.SeatFilter;
import io.micrometer.core.instrument.MeterRegistry;
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

    @Bean SeatStateCache seatStateCache(StringRedisTemplate redis) { return new SeatStateCache(redis); }

    @Bean SeatSnapshots seatSnapshots(NamedParameterJdbcTemplate jdbc) { return new SeatSnapshots(jdbc); }

    @Bean(destroyMethod = "shutdown")
    SeatCacheUpdater seatCacheUpdater(SeatStateCache cache, MeterRegistry registry,
                                      @Value("${inventory.seat-cache.update-queue:10000}") int queueCapacity) {
        return new SeatCacheUpdater(cache, registry, queueCapacity);
    }

    /** Fast-reject filter in front of hold creation (P2.4); off with {@code inventory.fast-path.enabled=false}. */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "inventory.fast-path.enabled", havingValue = "true", matchIfMissing = true)
    SeatFilter redisSeatFilter(StringRedisTemplate redis, MeterRegistry registry) {
        return new RedisSeatFilter(redis, registry);
    }

    /** The decorated store becomes the one the read side uses; the JDBC store stays inside as fallback. */
    @Bean
    @Primary
    CatalogReadStore cachedCatalogReadStore(@Qualifier("jdbcCatalogReadStore") CatalogReadStore database,
                                            SeatStateCache cache, SeatSnapshots snapshots, MeterRegistry registry) {
        return new CachedCatalogReadStore(database, cache, snapshots, registry);
    }
}
