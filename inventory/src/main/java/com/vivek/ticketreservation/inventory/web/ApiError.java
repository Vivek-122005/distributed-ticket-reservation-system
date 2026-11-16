package com.vivek.ticketreservation.inventory.web;

public record ApiError(String code, String message, boolean retryable) { }
