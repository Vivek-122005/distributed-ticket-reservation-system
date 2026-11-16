package com.vivek.ticketreservation.inventory.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} jobs of the inventory module (hold expiry P2.1, reconciliation P2.5). */
@Configuration
@EnableScheduling
class SchedulingConfig { }
