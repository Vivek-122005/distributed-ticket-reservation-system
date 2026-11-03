package com.vivek.ticketreservation.application;

import com.vivek.ticketreservation.api.HoldResponse;
import com.vivek.ticketreservation.domain.Hold;
import com.vivek.ticketreservation.infrastructure.HoldRepository;
import com.vivek.ticketreservation.infrastructure.SeatInventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class InventoryApplicationService {
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private final SeatInventoryRepository seats;
    private final HoldRepository holds;

    public InventoryApplicationService(SeatInventoryRepository seats, HoldRepository holds) { this.seats = seats; this.holds = holds; }

    @Transactional
    public HoldResponse createHold(UUID eventId, UUID userId, List<String> seatCodes) {
        List<String> normalized = seatCodes.stream().map(String::trim).map(String::toUpperCase).distinct().sorted(Comparator.naturalOrder()).toList();
        if (normalized.size() != seatCodes.size()) throw new IllegalArgumentException("Seat codes must be unique");
        UUID holdId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(HOLD_DURATION);
        holds.saveAndFlush(new Hold(holdId, eventId, userId, expiresAt));
        for (String seatCode : normalized) {
            if (seats.claimAvailableSeat(eventId, seatCode, holdId, userId, expiresAt) != 1) throw new SeatUnavailableException();
        }
        return new HoldResponse(holdId, "HELD", expiresAt, normalized);
    }
}
