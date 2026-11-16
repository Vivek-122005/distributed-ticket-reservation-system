package com.vivek.ticketreservation.inventory.api;

/**
 * Operations other modules may perform on seat inventory.
 *
 * <p>Pattern: Facade + Interface Segregation, see docs/lld/DESIGN_PATTERNS.md#9-facade and
 * docs/lld/SOLID.md. Confirm and release are added in P1.4; catalog admin lives in a separate
 * interface (P6.9) so callers only see what they use.
 */
public interface InventoryApi {

    /**
     * Atomically holds every requested seat or none of them.
     *
     * @throws SeatUnavailableException if any requested seat is not available
     * @throws IllegalArgumentException if the request is invalid (for example duplicate seat codes)
     */
    HoldResult hold(HoldCommand command);
}
