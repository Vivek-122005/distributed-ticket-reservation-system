package com.vivek.ticketreservation.inventory.api;

import java.util.UUID;

/** The show exists but its sale is not open (BR-03); nothing was held. Part of the published API. */
public class ShowNotOnSaleException extends RuntimeException {
    public ShowNotOnSaleException(UUID showId) { super("Show " + showId + " is not on sale"); }
}
