package com.vivek.ticketreservation.inventory.application.port;

/**
 * Outbound port: publishes domain events of the inventory module ({@code domain.event.*}).
 *
 * <p>Today the adapter delivers them in-process <b>after commit</b> (Spring application events), which is
 * enough for local reactions (metrics, cache updates). It is not durable: an event can be lost if the
 * process dies right after commit. P5.2 swaps the adapter for the transactional outbox without changing
 * any caller (DIP), and the reconciliation job (P2.5) repairs anything a lost event left behind.
 */
public interface InventoryEventPublisher {
    void publish(Object domainEvent);
}
