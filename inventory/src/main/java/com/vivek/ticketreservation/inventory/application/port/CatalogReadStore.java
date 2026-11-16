package com.vivek.ticketreservation.inventory.application.port;

import com.vivek.ticketreservation.inventory.api.CatalogQuery.EventView;
import com.vivek.ticketreservation.inventory.api.CatalogQuery.ShowView;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery.SeatMapView;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port of the read side: returns ready-made views, no aggregates (CQRS-lite). Kept apart from
 * the write-side stores so its implementation can become a cache or a replica on its own.
 */
public interface CatalogReadStore {

    List<EventView> publishedEvents(int offset, int limit);

    long countPublishedEvents();

    /** Empty if the event does not exist or is not published. */
    Optional<List<ShowView>> showsOfPublishedEvent(UUID eventId);

    /** Empty if the show does not exist. */
    Optional<SeatMapView> seatMap(UUID showId, Optional<String> section);
}
