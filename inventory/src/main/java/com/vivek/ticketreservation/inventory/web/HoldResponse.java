package com.vivek.ticketreservation.inventory.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldResponse(UUID holdId, String status, Instant expiresAt, List<String> seatCodes) { }
