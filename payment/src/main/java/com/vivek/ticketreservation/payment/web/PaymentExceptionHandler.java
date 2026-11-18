package com.vivek.ticketreservation.payment.web;

import com.vivek.ticketreservation.common.web.ApiError;
import com.vivek.ticketreservation.payment.api.PaymentNotFoundException;
import com.vivek.ticketreservation.payment.domain.IllegalPaymentTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Pattern: centralised exception translation (DESIGN_PATTERNS.md#5), for the payment controllers only. */
@RestControllerAdvice(basePackageClasses = PaymentController.class)
class PaymentExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError notFound(PaymentNotFoundException e) { return ApiError.of("PAYMENT_NOT_FOUND", e.getMessage(), false); }

    @ExceptionHandler(IllegalPaymentTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError illegal(IllegalPaymentTransitionException e) { return ApiError.of("ILLEGAL_STATE_TRANSITION", e.getMessage(), false); }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError invalid(Exception e) { return ApiError.of("INVALID_REQUEST", "Request is malformed or fails validation", false); }
}
