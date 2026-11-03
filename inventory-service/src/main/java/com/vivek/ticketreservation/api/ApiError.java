package com.vivek.ticketreservation.api;

public record ApiError(String code, String message, boolean retryable) { }
