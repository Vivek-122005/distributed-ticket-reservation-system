/**
 * Outbound ports of the inventory module: what the use cases need from the outside world.
 *
 * <p>Pattern: Ports and Adapters (docs/lld/DESIGN_PATTERNS.md#1b). Implementations live in
 * {@code inventory.infrastructure}; ArchUnit forbids {@code application} from depending on
 * {@code infrastructure} (fixes SOLID.md deviation #2).
 */
package com.vivek.ticketreservation.inventory.application.port;
