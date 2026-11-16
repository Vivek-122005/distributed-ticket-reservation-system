package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.inventory.api.ShowNotFoundException;
import com.vivek.ticketreservation.inventory.api.ShowNotOnSaleException;
import com.vivek.ticketreservation.inventory.domain.Hold;
import com.vivek.ticketreservation.inventory.infrastructure.HoldRepository;
import com.vivek.ticketreservation.inventory.infrastructure.ShowRepository;
import com.vivek.ticketreservation.inventory.infrastructure.ShowRow;
import com.vivek.ticketreservation.inventory.infrastructure.ShowSeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Holds seats of one show all-or-nothing using a conditional update per seat inside one transaction.
 *
 * <p>Pattern: Compare-and-Set, see docs/lld/DESIGN_PATTERNS.md#3-compare-and-set-conditional-update.
 * Seats are sorted before claiming so concurrent multi-seat requests lock rows in the same order.
 * Known deviation: depends on Spring Data repositories directly instead of a port (SOLID.md #2, P1.3).
 */
@Service
public class CreateHoldUseCase {
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private final ShowRepository shows;
    private final ShowSeatRepository seats;
    private final HoldRepository holds;

    public CreateHoldUseCase(ShowRepository shows, ShowSeatRepository seats, HoldRepository holds) {
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
        for (String seatCode : normalized) {
            if (seats.claimAvailableSeat(command.showId(), seatCode, holdId, command.userId(), expiresAt) != 1) throw new SeatUnavailableException();
        }
        return new HoldResult(holdId, command.showId(), expiresAt, normalized);
    }
}
