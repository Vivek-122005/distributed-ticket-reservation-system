/**
 * Published API of the inventory module: the only package other modules may use.
 *
 * <p>Pattern: Facade, see docs/lld/DESIGN_PATTERNS.md#9-facade. In stage 1 (ADR 002) the booking
 * module calls {@link com.vivek.ticketreservation.inventory.api.InventoryApi} in-process; on
 * extraction (P7.8) an HTTP adapter implements the same interface. Enforced by ArchUnit in
 * {@code ticketing-app} (ModuleBoundaryTest).
 */
package com.vivek.ticketreservation.inventory.api;
