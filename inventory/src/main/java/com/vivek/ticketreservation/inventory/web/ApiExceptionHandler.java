package com.vivek.ticketreservation.inventory.web;

import com.vivek.ticketreservation.common.web.ApiError;
import com.vivek.ticketreservation.inventory.api.EventNotFoundException;
import com.vivek.ticketreservation.inventory.api.HoldConflictException;
import com.vivek.ticketreservation.inventory.api.HoldNotFoundException;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.inventory.api.ShowNotFoundException;
import com.vivek.ticketreservation.inventory.api.ShowNotOnSaleException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;

/**
 * Pattern: centralised exception translation, see docs/lld/DESIGN_PATTERNS.md#5. Scoped to the
 * inventory controllers (P3.3): each module maps its own exceptions, so no module's handler decides
 * another module's error codes.
 */
@RestControllerAdvice(basePackageClasses = InventoryController.class)
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

    @ExceptionHandler(EventNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError eventNotFound(EventNotFoundException error) { return ApiError.of("EVENT_NOT_FOUND", error.getMessage(), false); }

    /** Bean Validation on bodies, malformed ids, missing headers: all the same stable INVALID_REQUEST shape. */
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError malformed(Exception error) { return ApiError.of("INVALID_REQUEST", "Request is malformed or fails validation", false); }

    /**
     * Database or Redis unreachable or timing out, or a transient lock failure: the transaction was rolled
     * back, nothing changed, and the same request may succeed later (schema Unavailable, retryable).
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiError unavailable(Exception error) {
        return ApiError.of("SERVICE_UNAVAILABLE", "A dependency is unavailable, retry with backoff", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError invalid(IllegalArgumentException error) { return ApiError.of("INVALID_REQUEST", error.getMessage(), false); }
}
