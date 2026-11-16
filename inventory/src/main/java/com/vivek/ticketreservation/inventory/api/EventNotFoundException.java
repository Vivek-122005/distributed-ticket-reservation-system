package com.vivek.ticketreservation.inventory.api;

import java.util.UUID;

/** The event does not exist or is not published. */
public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(UUID eventId) { super("Event " + eventId + " not found"); }
}
