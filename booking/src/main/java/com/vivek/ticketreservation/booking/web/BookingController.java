package com.vivek.ticketreservation.booking.web;

import com.vivek.ticketreservation.booking.application.BookingQueries;
import com.vivek.ticketreservation.booking.application.CancelBookingUseCase;
import com.vivek.ticketreservation.booking.application.CreateBookingUseCase;
import com.vivek.ticketreservation.booking.domain.BookingStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
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
    private final CancelBookingUseCase cancelBooking;
    private final BookingQueries queries;

    public BookingController(CreateBookingUseCase createBooking, CancelBookingUseCase cancelBooking, BookingQueries queries) {
        this.createBooking = createBooking;
        this.cancelBooking = cancelBooking;
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

    @PostMapping("/{bookingId}/cancel")
    public BookingResponse cancel(@PathVariable UUID bookingId, @RequestHeader(USER) UUID userId) {
        return BookingResponse.of(cancelBooking.execute(bookingId, userId));
    }

    @GetMapping
    public PageResponse history(@RequestHeader(USER) UUID userId,
                                @RequestParam(required = false) BookingStatus status,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        BookingQueries.Page result = queries.history(userId, status, page, size);
        return new PageResponse(result.items().stream().map(BookingResponse::of).toList(),
                result.page(), result.size(), result.totalItems());
    }

    /** Schema BookingPage. */
    public record PageResponse(List<BookingResponse> items, int page, int size, long totalItems) { }
}
