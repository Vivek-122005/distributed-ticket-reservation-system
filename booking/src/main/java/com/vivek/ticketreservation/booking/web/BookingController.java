package com.vivek.ticketreservation.booking.web;

import com.vivek.ticketreservation.booking.application.BookingQueries;
import com.vivek.ticketreservation.booking.application.CreateBookingUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * Inbound HTTP adapter of the booking API (docs/api/booking-service.yaml). Only binding and mapping;
 * the rules live in the use cases. {@code X-User-Id} stands in for the JWT subject until P6.2.
 */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {
    static final String USER = "X-User-Id";
    private final CreateBookingUseCase createBooking;
    private final BookingQueries queries;

    public BookingController(CreateBookingUseCase createBooking, BookingQueries queries) {
        this.createBooking = createBooking;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> create(@RequestHeader(USER) UUID userId, @Valid @RequestBody CreateBookingRequest request) {
        BookingResponse booking = BookingResponse.of(createBooking.execute(
                new CreateBookingUseCase.Command(userId, request.showId(), request.seatCodes())));
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + booking.bookingId())).body(booking);
    }

    @GetMapping("/{bookingId}")
    public BookingResponse get(@PathVariable UUID bookingId, @RequestHeader(USER) UUID userId) {
        return BookingResponse.of(queries.get(bookingId, userId));
    }
}
