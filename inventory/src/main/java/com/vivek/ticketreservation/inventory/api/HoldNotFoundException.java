package com.vivek.ticketreservation.inventory.api;

import java.util.UUID;

/** No such hold for this user. A hold of another user is reported the same way, so ids can't be probed. */
public class HoldNotFoundException extends RuntimeException {
    public HoldNotFoundException(UUID holdId) { super("Hold " + holdId + " not found"); }
}
