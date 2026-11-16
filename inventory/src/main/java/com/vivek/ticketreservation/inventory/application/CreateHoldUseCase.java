package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.HeldSeat;
import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldState;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.inventory.api.ShowNotFoundException;
import com.vivek.ticketreservation.inventory.api.ShowNotOnSaleException;
import com.vivek.ticketreservation.inventory.application.port.HoldStore;
import com.vivek.ticketreservation.inventory.application.port.SeatLockingStrategy;
import com.vivek.ticketreservation.inventory.application.port.SeatLockingStrategy.ClaimedSeat;
import com.vivek.ticketreservation.inventory.application.port.ShowStore;
import com.vivek.ticketreservation.inventory.application.port.ShowSeatStore.SeatVersion;
import com.vivek.ticketreservation.inventory.domain.Hold;
import com.vivek.ticketreservation.inventory.domain.Show;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Holds seats of one show all-or-nothing (FR-03).
 *
 * <p>Depends only on ports (DIP, SOLID.md #2 fixed in P1.3): {@link ShowStore}, {@link HoldStore} and
 * the {@link SeatLockingStrategy} chosen by configuration (P1.7). If the strategy claims fewer seats
 * than requested, the exception rolls back the whole transaction, including the hold row and the
 * seats that were claimed. Expiry is computed by the database clock (SRS A3).
 */
@Service
public class CreateHoldUseCase {
    static final Duration HOLD_DURATION = Duration.ofMinutes(5);   // BR-02
    private final ShowStore shows;
    private final SeatLockingStrategy locking;
    private final HoldStore holds;
    private final SeatEvents events;

    public CreateHoldUseCase(ShowStore shows, SeatLockingStrategy locking, HoldStore holds, SeatEvents events) {
        this.shows = shows; this.locking = locking; this.holds = holds; this.events = events;
    }

    @Transactional
    public HoldResult execute(HoldCommand command) {
        List<String> requested = command.seatCodes();
        List<String> normalized = SeatCodes.normalize(requested);
        if (normalized.size() != requested.size()) throw new IllegalArgumentException("Seat codes must be unique");

        // BR-03. A sale closing concurrently with this check is harmless: the seat claims below still
        // decide ownership, and a hold granted in the same instant as the close simply expires or is paid.
        Show show = shows.find(command.showId()).orElseThrow(() -> new ShowNotFoundException(command.showId()));
        if (!show.isOnSale()) throw new ShowNotOnSaleException(command.showId());

        Hold hold = holds.createHeld(UUID.randomUUID(), command.showId(), command.userId(), normalized, HOLD_DURATION);
        List<ClaimedSeat> claimed = locking.claim(command.showId(), normalized, hold.id(), command.userId(), hold.expiresAt());
        if (claimed.size() != normalized.size()) throw new SeatUnavailableException();
        events.held(command.showId(), claimed.stream().map(c -> new SeatVersion(c.seatCode(), c.version())).toList(), hold.expiresAt());

        List<HeldSeat> held = claimed.stream()
                .map(seat -> new HeldSeat(seat.seatCode(), seat.priceMinor()))
                .sorted(Comparator.comparing(HeldSeat::seatCode))
                .toList();
        long total = held.stream().mapToLong(HeldSeat::priceMinor).sum();
        return new HoldResult(hold.id(), command.showId(), HoldState.HELD, hold.expiresAt(), held, total, show.currency());
    }
}
