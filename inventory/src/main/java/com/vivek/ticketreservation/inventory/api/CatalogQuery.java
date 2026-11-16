package com.vivek.ticketreservation.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * Public read side of the catalog: published events and their shows (FR-01).
 *
 * <p>Separate from {@link InventoryApi} (hold lifecycle) and {@link SeatMapQuery} by Interface
 * Segregation: the booking saga never sees catalog reads, and the read path can be cached or moved to a
 * replica (P2.3, P7.5) without touching the write path. See docs/lld/SOLID.md (I).
 */
public interface CatalogQuery {

    /** Published events, newest first. */
    Page<EventView> listEvents(int page, int size);

    /** Shows of a published event in start order. @throws EventNotFoundException if unknown or unpublished */
    List<ShowView> listShows(UUID eventId);

    record EventView(UUID eventId, String eventCode, String name, String description, String status) { }

    record ShowView(UUID showId, UUID eventId, UUID venueId, String venueName, java.time.Instant startsAt,
                    java.time.Instant saleOpensAt, String status, int availableSeats) { }

    record Page<T>(List<T> items, int page, int size, long totalItems) { }
}
