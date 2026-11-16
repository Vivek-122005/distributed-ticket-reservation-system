package com.vivek.ticketreservation.inventory.application.port;

import com.vivek.ticketreservation.inventory.domain.Show;

import java.util.Optional;
import java.util.UUID;

/** Outbound port: reads shows. Admin writes arrive with CatalogAdminApi (P6.9). */
public interface ShowStore {
    Optional<Show> find(UUID showId);
}
