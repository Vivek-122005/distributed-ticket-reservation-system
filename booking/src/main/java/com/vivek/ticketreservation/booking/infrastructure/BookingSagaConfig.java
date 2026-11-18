package com.vivek.ticketreservation.booking.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** Wiring for the saga: the clock it schedules checks with (tests can replace it). */
@Configuration
@EnableScheduling
class BookingSagaConfig {
    @Bean
    @ConditionalOnMissingBean
    Clock clock() { return Clock.systemUTC(); }
}
