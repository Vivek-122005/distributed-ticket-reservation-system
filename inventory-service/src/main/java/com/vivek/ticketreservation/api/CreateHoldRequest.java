package com.vivek.ticketreservation.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateHoldRequest(@NotEmpty @Size(max = 8) List<@Size(min = 1, max = 20) String> seatCodes) { }
