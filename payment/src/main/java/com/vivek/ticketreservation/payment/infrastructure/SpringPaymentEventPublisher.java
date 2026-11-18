package com.vivek.ticketreservation.payment.infrastructure;

import com.vivek.ticketreservation.payment.application.port.PaymentEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * In-process adapter of {@link PaymentEventPublisher}: Spring application events, delivered
 * synchronously to the booking saga's listener. Payment writes are single auto-committed statements,
 * so an event is only published after its change is durable. Replaced by the outbox in P5.2.
 * Pattern: Observer (docs/lld/DESIGN_PATTERNS.md#14).
 */
@Component
class SpringPaymentEventPublisher implements PaymentEventPublisher {
    private final ApplicationEventPublisher events;

    SpringPaymentEventPublisher(ApplicationEventPublisher events) { this.events = events; }

    @Override
    public void publish(Object event) { events.publishEvent(event); }
}
