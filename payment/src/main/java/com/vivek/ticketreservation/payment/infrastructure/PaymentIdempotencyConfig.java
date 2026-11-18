package com.vivek.ticketreservation.payment.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivek.ticketreservation.common.idempotency.IdempotencyFilter;
import com.vivek.ticketreservation.common.idempotency.JdbcIdempotencyStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;

/**
 * The shared idempotency filter (P3.5) reused for checkout, with payment's own table: the second
 * module to adopt it, with no change to the filter (OCP).
 */
@Configuration
class PaymentIdempotencyConfig {

    @Bean
    FilterRegistrationBean<IdempotencyFilter> paymentIdempotencyFilter(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(new IdempotencyFilter(
                new JdbcIdempotencyStore(jdbc, "payment.idempotency_keys"), json, "X-User-Id",
                Duration.ofHours(24), Duration.ofSeconds(30)));
        registration.addUrlPatterns("/api/v1/payments/*");
        registration.setName("paymentIdempotencyFilter");
        return registration;
    }
}
