package com.vivek.ticketreservation.booking.web;

import com.vivek.ticketreservation.booking.application.BookingView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response DTO (schema Booking). {@code holdExpiresAt} is shown only while the user still has to pay. */
public record BookingResponse(UUID bookingId, UUID showId, String status, List<Seat> seats, long amountMinor,
                              String currency, Instant holdExpiresAt, Instant createdAt) {

    public record Seat(String seatCode, long priceMinor) { }

    static BookingResponse of(BookingView view) {
        boolean pending = view.status().name().equals("PENDING_PAYMENT");
        return new BookingResponse(view.bookingId(), view.showId(), view.status().name(),
                view.seats().stream().map(s -> new Seat(s.seatCode(), s.priceMinor())).toList(),
                view.amountMinor(), view.currency(), pending ? view.holdExpiresAt() : null, view.createdAt());
    }
}
