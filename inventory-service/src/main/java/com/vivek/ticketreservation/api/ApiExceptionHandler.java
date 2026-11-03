package com.vivek.ticketreservation.api;

import com.vivek.ticketreservation.application.SeatUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(SeatUnavailableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError unavailable() { return new ApiError("SEAT_UNAVAILABLE", "One or more requested seats are no longer available.", false); }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError invalid(IllegalArgumentException error) { return new ApiError("INVALID_REQUEST", error.getMessage(), false); }
}
