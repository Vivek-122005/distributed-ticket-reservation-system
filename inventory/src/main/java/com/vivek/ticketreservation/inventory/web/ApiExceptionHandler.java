package com.vivek.ticketreservation.inventory.web;

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
    ApiError unavailable() { return new ApiError("SEAT_UNAVAILABLE", "One or more requested seats are no longer available.", false); }

    @ExceptionHandler(ShowNotOnSaleException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError notOnSale(ShowNotOnSaleException error) { return new ApiError("SHOW_NOT_ON_SALE", error.getMessage(), false); }

    @ExceptionHandler(ShowNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError showNotFound(ShowNotFoundException error) { return new ApiError("SHOW_NOT_FOUND", error.getMessage(), false); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError invalid(IllegalArgumentException error) { return new ApiError("INVALID_REQUEST", error.getMessage(), false); }
}
