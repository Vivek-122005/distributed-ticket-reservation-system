package com.vivek.ticketreservation.inventory.web;

import com.vivek.ticketreservation.inventory.api.CatalogQuery;
import com.vivek.ticketreservation.inventory.api.CatalogQuery.EventView;
import com.vivek.ticketreservation.inventory.api.CatalogQuery.Page;
import com.vivek.ticketreservation.inventory.api.CatalogQuery.ShowView;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery.SeatMapView;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public, read-only catalog endpoints (FR-01, FR-02). The query views are transport-free records whose
 * fields match the contract schemas, so they are returned as-is instead of being copied into DTOs.
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogQuery catalog;
    private final SeatMapQuery seatMaps;

    public CatalogController(CatalogQuery catalog, SeatMapQuery seatMaps) { this.catalog = catalog; this.seatMaps = seatMaps; }

    @GetMapping("/events")
    public Page<EventView> listEvents(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return catalog.listEvents(page, size);
    }

    @GetMapping("/events/{eventId}/shows")
    public List<ShowView> listShows(@PathVariable UUID eventId) { return catalog.listShows(eventId); }

    @GetMapping("/shows/{showId}/seats")
    public SeatMapView seatMap(@PathVariable UUID showId, @RequestParam Optional<String> section) {
        return seatMaps.seatMap(showId, section);
    }
}
