package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.application.port.SeatFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.UUID;

/**
 * Null Object for {@link SeatFilter}: used when the Redis fast path is off (no cache, or
 * {@code inventory.fast-path.enabled=false}). Every request goes straight to Postgres. Always defined;
 * the Redis filter, when enabled, is {@code @Primary} and wins. (Not {@code @ConditionalOnMissingBean}:
 * that is only reliable in auto-configuration, not between ordinary configuration classes.)
 */
@Configuration
class NoSeatFilter {

    @Bean
    SeatFilter passThroughSeatFilter() {
        return new SeatFilter() {
            @Override public Reservation tryReserve(UUID showId, List<String> seatCodes) { return Reservation.skipped(); }
            @Override public void cancel(Reservation reservation) { }
        };
    }
}
