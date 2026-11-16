package com.vivek.ticketreservation.inventory.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Full transition tables of the three enum state machines: every pair is listed, nothing is implicit. */
class StatusTransitionsTest {

    @ParameterizedTest(name = "seat {0} -> {1}: {2}")
    @CsvSource({
            "AVAILABLE,AVAILABLE,false", "AVAILABLE,HELD,true",  "AVAILABLE,BOOKED,false",
            "HELD,AVAILABLE,true",       "HELD,HELD,true",       "HELD,BOOKED,true",
            "BOOKED,AVAILABLE,false",    "BOOKED,HELD,false",    "BOOKED,BOOKED,false"})
    void seat(SeatStatus from, SeatStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest(name = "hold {0} -> {1}: {2}")
    @CsvSource({
            "HELD,HELD,false",      "HELD,CONFIRMED,true",       "HELD,RELEASED,true",       "HELD,EXPIRED,true",
            "CONFIRMED,HELD,false", "CONFIRMED,RELEASED,false",  "CONFIRMED,EXPIRED,false",
            "RELEASED,HELD,false",  "RELEASED,CONFIRMED,false",  "EXPIRED,HELD,false",       "EXPIRED,CONFIRMED,false"})
    void hold(HoldStatus from, HoldStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest(name = "show {0} -> {1}: {2}")
    @CsvSource({
            "SCHEDULED,ON_SALE,true", "SCHEDULED,SOLD_OUT,false", "SCHEDULED,CLOSED,true",
            "ON_SALE,SOLD_OUT,true",  "ON_SALE,CLOSED,true",      "ON_SALE,SCHEDULED,false",
            "SOLD_OUT,ON_SALE,true",  "SOLD_OUT,CLOSED,true",
            "CLOSED,ON_SALE,false",   "CLOSED,SCHEDULED,false"})
    void show(ShowStatus from, ShowStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }
}
