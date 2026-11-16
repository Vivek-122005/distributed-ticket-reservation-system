package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.HeldSeat;
import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.inventory.api.ShowNotFoundException;
import com.vivek.ticketreservation.inventory.api.ShowNotOnSaleException;
import com.vivek.ticketreservation.inventory.domain.Hold;
import com.vivek.ticketreservation.inventory.infrastructure.HoldRepository;
import com.vivek.ticketreservation.inventory.infrastructure.ShowRepository;
import com.vivek.ticketreservation.inventory.infrastructure.ShowRow;
import com.vivek.ticketreservation.inventory.infrastructure.JdbcSeatClaimRepository;
import com.vivek.ticketreservation.inventory.infrastructure.JdbcSeatClaimRepository.ClaimedSeat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Holds seats of one show all-or-nothing with one batched conditional update (P1.1).
 *
 * <p>Pattern: Compare-and-Set, see docs/lld/DESIGN_PATTERNS.md#3-compare-and-set-conditional-update.
 * One round trip claims every seat that is still AVAILABLE, locking rows in seat-code order; if fewer
 * rows come back than were requested, the exception rolls back the whole transaction, including the
 * hold row and the seats that were claimed.
 * Known deviation: depends on Spring Data repositories directly instead of a port (SOLID.md #2, P1.3).
 */
@Service
public class CreateHoldUseCase {
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private final ShowRepository shows;
    private final JdbcSeatClaimRepository seats;
    private final HoldRepository holds;

    public CreateHoldUseCase(ShowRepository shows, JdbcSeatClaimRepository seats, HoldRepository holds) {
        this.shows = shows; this.seats = seats; this.holds = holds;
    }

    @Transactional
    public HoldResult execute(HoldCommand command) {
        List<String> requested = command.seatCodes();
        List<String> normalized = requested.stream().map(String::trim).map(String::toUpperCase).distinct().sorted(Comparator.naturalOrder()).toList();
        if (normalized.size() != requested.size()) throw new IllegalArgumentException("Seat codes must be unique");

        // BR-03. A sale closing concurrently with this check is harmless: the seat claims below still
        // decide ownership, and a hold granted in the same instant as the close simply expires or is paid.
        ShowRow show = shows.findById(command.showId()).orElseThrow(() -> new ShowNotFoundException(command.showId()));
        if (!show.isOnSale()) throw new ShowNotOnSaleException(command.showId());

        UUID holdId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(HOLD_DURATION);
        holds.saveAndFlush(new Hold(holdId, command.showId(), command.userId(), expiresAt));
        List<ClaimedSeat> claimed = seats.claimAvailable(command.showId(), normalized, holdId, command.userId(), expiresAt);
        if (claimed.size() != normalized.size()) throw new SeatUnavailableException();

        List<HeldSeat> held = claimed.stream()
                .map(seat -> new HeldSeat(seat.seatCode(), seat.priceMinor()))
                .sorted(Comparator.comparing(HeldSeat::seatCode))
                .toList();
        long total = held.stream().mapToLong(HeldSeat::priceMinor).sum();
        return new HoldResult(holdId, command.showId(), expiresAt, held, total, show.getCurrency());
    }
}
