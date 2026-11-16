package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.HoldCommand;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.SeatUnavailableException;
import com.vivek.ticketreservation.inventory.application.port.SeatFilter;
import com.vivek.ticketreservation.inventory.application.port.SeatFilter.Reservation;
import com.vivek.ticketreservation.inventory.application.port.SeatFilter.Verdict;
import org.springframework.stereotype.Component;

/**
 * Hold creation with the fast-reject filter in front (P2.4): "cache as filter, database as authority".
 *
 * <ol>
 *   <li>Ask the {@link SeatFilter} first, <b>outside any transaction</b>, so no database connection is
 *       held while Redis answers (the P1.8 failure, ADR 003).</li>
 *   <li>REJECTED: fail at once, Postgres never sees the request. This is where a flash sale's thousands
 *       of losers go.</li>
 *   <li>RESERVED or SKIPPED: run the real {@link CreateHoldUseCase} transaction, which decides.</li>
 *   <li>If that fails, cancel the pending marks so the seats are not shown as taken for nothing.</li>
 * </ol>
 * On success the pending marks are left in place; the committed {@code SeatsChanged} event replaces them
 * with the real HELD state (a higher version), and if that event were lost they simply time out.
 */
@Component
class FilteredCreateHold {
    private final SeatFilter filter;
    private final CreateHoldUseCase createHold;

    FilteredCreateHold(SeatFilter filter, CreateHoldUseCase createHold) { this.filter = filter; this.createHold = createHold; }

    HoldResult execute(HoldCommand command) {
        Reservation reservation = filter.tryReserve(command.showId(), SeatCodes.normalize(command.seatCodes()));
        if (reservation.verdict() == Verdict.REJECTED) throw new SeatUnavailableException();
        try {
            return createHold.execute(command);
        } catch (RuntimeException refused) {
            filter.cancel(reservation);
            throw refused;
        }
    }
}
