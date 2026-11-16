package com.vivek.ticketreservation.inventory.api;

/** At least one requested seat is not available; nothing was held. Part of the published API. */
public class SeatUnavailableException extends RuntimeException {
    public SeatUnavailableException() { super("One or more requested seats are unavailable"); }
}
