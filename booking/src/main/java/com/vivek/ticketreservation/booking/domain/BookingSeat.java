package com.vivek.ticketreservation.booking.domain;

import java.util.Objects;

/** One booked seat, copied from the hold at booking time (code and the price the user pays, minor units). */
public record BookingSeat(String seatCode, long priceMinor) {
    public BookingSeat {
        Objects.requireNonNull(seatCode);
        if (priceMinor < 0) throw new IllegalArgumentException("price must not be negative");
    }
}
