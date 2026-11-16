package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.CatalogQuery;
import com.vivek.ticketreservation.inventory.api.EventNotFoundException;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery;
import com.vivek.ticketreservation.inventory.api.ShowNotFoundException;
import com.vivek.ticketreservation.inventory.application.port.CatalogReadStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of the module: implements the two query interfaces over {@link CatalogReadStore}. */
@Component
@Transactional(readOnly = true)
class CatalogQueries implements CatalogQuery, SeatMapQuery {
    static final int MAX_PAGE_SIZE = 100;
    private final CatalogReadStore store;

    CatalogQueries(CatalogReadStore store) { this.store = store; }

    @Override
    public Page<EventView> listEvents(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("page >= 0 and 1 <= size <= 100");
        return new Page<>(store.publishedEvents(page * size, size), page, size, store.countPublishedEvents());
    }

    @Override
    public List<ShowView> listShows(UUID eventId) {
        return store.showsOfPublishedEvent(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    }

    @Override
    public SeatMapView seatMap(UUID showId, Optional<String> section) {
        return store.seatMap(showId, section.map(String::trim).map(String::toUpperCase))
                .orElseThrow(() -> new ShowNotFoundException(showId));
    }
}
