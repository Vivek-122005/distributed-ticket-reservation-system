package com.vivek.ticketreservation.booking.web;

import com.vivek.ticketreservation.booking.application.BookingNotFoundException;
import com.vivek.ticketreservation.booking.application.PaymentUnavailableException;
import com.vivek.ticketreservation.booking.application.port.SeatReservationException;
import com.vivek.ticketreservation.booking.domain.IllegalStateTransitionException;
import com.vivek.ticketreservation.common.web.ApiError;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Pattern: centralised exception translation (docs/lld/DESIGN_PATTERNS.md#5), for the booking controllers only. */
@RestControllerAdvice(basePackageClasses = BookingController.class)
class BookingExceptionHandler {

    @ExceptionHandler(SeatReservationException.class)
    ResponseEntity<ApiError> reservation(SeatReservationException error) {
        HttpStatus status = switch (error.reason()) {
            case SHOW_NOT_FOUND, HOLD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(ApiError.of(error.reason().name(), error.getMessage(), false));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError notFound(BookingNotFoundException error) { return ApiError.of("BOOKING_NOT_FOUND", error.getMessage(), false); }

    @ExceptionHandler(IllegalStateTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError illegal(IllegalStateTransitionException error) {
        return ApiError.of("ILLEGAL_STATE_TRANSITION", error.getMessage(), false);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError malformed(Exception error) { return ApiError.of("INVALID_REQUEST", "Request is malformed or fails validation", false); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError invalid(IllegalArgumentException error) { return ApiError.of("INVALID_REQUEST", error.getMessage(), false); }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class, PaymentUnavailableException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiError unavailable(Exception error) {
        return ApiError.of("SERVICE_UNAVAILABLE", "A dependency is unavailable, retry with backoff", true);
    }
}
