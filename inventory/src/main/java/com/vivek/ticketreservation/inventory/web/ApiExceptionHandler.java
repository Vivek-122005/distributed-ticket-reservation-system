package com.vivek.ticketreservation.inventory.web;

import com.vivek.ticketreservation.inventory.api.HoldConflictException;
import com.vivek.ticketreservation.inventory.api.HoldNotFoundException;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.inventory.api.ShowNotFoundException;
import com.vivek.ticketreservation.inventory.api.ShowNotOnSaleException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Pattern: centralised exception translation, see docs/lld/DESIGN_PATTERNS.md#5. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(SeatUnavailableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError unavailable() { return ApiError.of("SEAT_UNAVAILABLE", "One or more requested seats are no longer available.", false); }

    @ExceptionHandler(ShowNotOnSaleException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError notOnSale(ShowNotOnSaleException error) { return ApiError.of("SHOW_NOT_ON_SALE", error.getMessage(), false); }

    @ExceptionHandler(ShowNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError showNotFound(ShowNotFoundException error) { return ApiError.of("SHOW_NOT_FOUND", error.getMessage(), false); }

    @ExceptionHandler(HoldNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError holdNotFound(HoldNotFoundException error) { return ApiError.of("HOLD_NOT_FOUND", error.getMessage(), false); }

    @ExceptionHandler(HoldConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError holdConflict(HoldConflictException error) { return ApiError.of(error.reason().name(), error.getMessage(), false); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError invalid(IllegalArgumentException error) { return ApiError.of("INVALID_REQUEST", error.getMessage(), false); }
}
