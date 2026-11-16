package com.vivek.ticketreservation.inventory.api;

import java.util.UUID;

/** The requested show does not exist. Part of the published API. */
public class ShowNotFoundException extends RuntimeException {
    public ShowNotFoundException(UUID showId) { super("Show " + showId + " does not exist"); }
}
