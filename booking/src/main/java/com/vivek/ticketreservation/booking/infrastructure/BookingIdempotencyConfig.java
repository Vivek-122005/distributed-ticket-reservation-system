package com.vivek.ticketreservation.booking.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivek.ticketreservation.common.idempotency.IdempotencyFilter;
import com.vivek.ticketreservation.common.idempotency.IdempotencyStore;
import com.vivek.ticketreservation.common.idempotency.JdbcIdempotencyStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;

/**
 * Puts the shared {@link IdempotencyFilter} (common, P3.5) in front of booking's mutating endpoints,
 * backed by booking's own {@code booking.idempotency_keys} table. Keys are kept 24 h (API contract).
 */
@Configuration
class BookingIdempotencyConfig {

    @Bean
    IdempotencyStore bookingIdempotencyStore(NamedParameterJdbcTemplate jdbc) {
        return new JdbcIdempotencyStore(jdbc, "booking.idempotency_keys");
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> bookingIdempotencyFilter(
            IdempotencyStore bookingIdempotencyStore, ObjectMapper json,
            @Value("${booking.idempotency.ttl:24h}") Duration ttl,
            @Value("${booking.idempotency.lease:30s}") Duration lease) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(
                new IdempotencyFilter(bookingIdempotencyStore, json, "X-User-Id", ttl, lease));
        registration.addUrlPatterns("/api/v1/bookings", "/api/v1/bookings/*");
        registration.setName("bookingIdempotencyFilter");
        return registration;
    }
}
