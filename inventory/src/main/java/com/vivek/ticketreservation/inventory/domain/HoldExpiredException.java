package com.vivek.ticketreservation.inventory.domain;

import java.util.UUID;

/** The hold passed its expiry before it could be confirmed; the caller must start the refund path. */
public class HoldExpiredException extends RuntimeException {
    public HoldExpiredException(UUID holdId) { super("Hold " + holdId + " expired before confirmation"); }
}
