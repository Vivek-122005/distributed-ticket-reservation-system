package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.HeldSeat;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.api.HoldState;
import com.vivek.ticketreservation.inventory.application.port.ShowSeatStore;
import com.vivek.ticketreservation.inventory.application.port.ShowStore;
import com.vivek.ticketreservation.inventory.domain.Hold;
import com.vivek.ticketreservation.inventory.domain.Show;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Builds the published {@link HoldResult} of a stored hold; shared by get, confirm and release (DRY). */
@Component
class HoldViews {
    private final ShowSeatStore seats;
    private final ShowStore shows;

    HoldViews(ShowSeatStore seats, ShowStore shows) { this.seats = seats; this.shows = shows; }

    HoldResult of(Hold hold, Instant now) {
        List<HeldSeat> priced = seats.prices(hold.showId(), hold.seatCodes()).stream()
                .map(seat -> new HeldSeat(seat.seatCode(), seat.priceMinor()))
                .toList();
        long total = priced.stream().mapToLong(HeldSeat::priceMinor).sum();
        String currency = shows.find(hold.showId()).map(Show::currency).orElseThrow();
        return new HoldResult(hold.id(), hold.showId(), HoldState.valueOf(hold.effectiveStatus(now).name()),
                hold.expiresAt(), priced, total, currency);
    }
}
