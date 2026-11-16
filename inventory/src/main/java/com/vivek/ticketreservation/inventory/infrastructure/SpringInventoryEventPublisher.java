package com.vivek.ticketreservation.inventory.infrastructure;

import com.vivek.ticketreservation.inventory.application.port.InventoryEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * {@link InventoryEventPublisher} on Spring application events. Listeners use
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, so nothing reacts to a change that is
 * later rolled back. Pattern: Observer (docs/lld/DESIGN_PATTERNS.md#14), in-process stage.
 */
@Component
class SpringInventoryEventPublisher implements InventoryEventPublisher {
    private final ApplicationEventPublisher events;

    SpringInventoryEventPublisher(ApplicationEventPublisher events) { this.events = events; }

    @Override
    public void publish(Object domainEvent) { events.publishEvent(domainEvent); }
}
