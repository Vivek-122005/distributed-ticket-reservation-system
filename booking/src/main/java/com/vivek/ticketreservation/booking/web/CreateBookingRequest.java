package com.vivek.ticketreservation.booking.web;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Body of {@code POST /bookings} (schema CreateBookingRequest). */
public record CreateBookingRequest(@NotNull UUID showId,
                                   @NotEmpty @Size(max = 8) List<@NotNull @Size(min = 1, max = 20) String> seatCodes) { }
