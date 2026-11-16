package com.vivek.ticketreservation.inventory.api;

import java.util.UUID;

/**
 * Operations other modules may perform on seat inventory: the hold lifecycle used by the booking saga.
 *
 * <p>Pattern: Facade + Interface Segregation, see docs/lld/DESIGN_PATTERNS.md#9-facade and
 * docs/lld/SOLID.md. Catalog admin (P6.9) and seat-map reads (P1.5) are separate interfaces, so
 * callers only see what they use. Every operation checks that the hold belongs to {@code userId}.
 */
public interface InventoryApi {

    /**
     * Atomically holds every requested seat or none of them.
     *
     * @throws SeatUnavailableException if any requested seat is not available
     * @throws ShowNotFoundException, ShowNotOnSaleException if the show can't be sold
     * @throws IllegalArgumentException if the request is invalid (for example duplicate seat codes)
     */
    HoldResult hold(HoldCommand command);

    /**
     * HELD → CONFIRMED and its seats HELD → BOOKED, after payment. Idempotent: confirming a confirmed
     * hold returns it unchanged.
     *
     * @throws HoldConflictException HOLD_EXPIRED if the hold expired first (refund path), or
     *         ILLEGAL_STATE_TRANSITION if it was released
     * @throws HoldNotFoundException if there is no such hold for this user
     */
    HoldResult confirm(UUID holdId, UUID userId);

    /**
     * HELD → RELEASED and its seats back to AVAILABLE (cancel, saga compensation). Idempotent:
     * releasing a released or expired hold returns it unchanged.
     *
     * @throws HoldConflictException HOLD_ALREADY_CONFIRMED if the hold was confirmed
     * @throws HoldNotFoundException if there is no such hold for this user
     */
    HoldResult release(UUID holdId, UUID userId);

    /** @throws HoldNotFoundException if there is no such hold for this user */
    HoldResult get(UUID holdId, UUID userId);
}
