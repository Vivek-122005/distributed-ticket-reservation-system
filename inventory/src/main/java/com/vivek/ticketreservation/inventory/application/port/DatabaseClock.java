package com.vivek.ticketreservation.inventory.application.port;

import java.time.Instant;

/**
 * Outbound port: the database's notion of "now" (transaction start time). SRS A3: replica clocks may
 * skew, so expiry decisions in Java use the same clock as the SQL claim ({@code now()}).
 */
public interface DatabaseClock {
    Instant now();
}
