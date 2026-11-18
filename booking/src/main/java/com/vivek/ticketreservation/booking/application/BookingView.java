package com.vivek.ticketreservation.booking.application;

import com.vivek.ticketreservation.booking.application.port.Payments.PaymentInfo;
import com.vivek.ticketreservation.booking.domain.Booking;
import com.vivek.ticketreservation.booking.domain.BookingStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A booking as the use cases return it, independent of HTTP; {@code payment} is null if none exists. */
public record BookingView(UUID bookingId, UUID showId, BookingStatus status, List<Seat> seats, long amountMinor,
                          String currency, Instant holdExpiresAt, Instant createdAt, PaymentInfo payment) {

    public record Seat(String seatCode, long priceMinor) { }

    static BookingView of(Booking booking, PaymentInfo payment) {
        return new BookingView(booking.id(), booking.showId(), booking.status(),
                booking.seats().stream().map(s -> new Seat(s.seatCode(), s.priceMinor())).toList(),
                booking.amountMinor(), booking.currency(), booking.holdExpiresAt(), booking.createdAt(), payment);
    }
}
