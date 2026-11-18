package com.vivek.ticketreservation.payment.application.port;

/** Announces payment outcomes ({@code payment.api} events). In-process today, outbox → Kafka in P5. */
public interface PaymentEventPublisher {
    void publish(Object event);
}
