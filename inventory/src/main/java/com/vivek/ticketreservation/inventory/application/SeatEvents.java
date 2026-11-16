package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.application.port.InventoryEventPublisher;
import com.vivek.ticketreservation.inventory.application.port.ShowSeatStore.SeatVersion;
import com.vivek.ticketreservation.inventory.domain.SeatStatus;
import com.vivek.ticketreservation.inventory.domain.event.SeatsChanged;
import com.vivek.ticketreservation.inventory.domain.event.SeatsChanged.SeatState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builds and publishes {@link SeatsChanged} for every seat write, so no use case forgets one (P2.3). */
@Component
public class SeatEvents {
    private final InventoryEventPublisher events;

    public SeatEvents(InventoryEventPublisher events) { this.events = events; }

    void held(UUID showId, List<SeatVersion> seats, Instant expiresAt) { publish(showId, seats, SeatStatus.HELD, expiresAt); }

    void booked(UUID showId, List<SeatVersion> seats) { publish(showId, seats, SeatStatus.BOOKED, null); }

    void released(UUID showId, List<SeatVersion> seats) { publish(showId, seats, SeatStatus.AVAILABLE, null); }

    private void publish(UUID showId, List<SeatVersion> seats, SeatStatus status, Instant expiresAt) {
        if (seats.isEmpty()) return;
        events.publish(new SeatsChanged(showId, seats.stream()
                .map(s -> new SeatState(s.seatCode(), status, expiresAt, s.version())).toList()));
    }
}
