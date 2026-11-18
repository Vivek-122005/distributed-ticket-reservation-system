package com.vivek.ticketreservation.payment.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivek.ticketreservation.payment.application.WebhookSignature;
import com.vivek.ticketreservation.payment.application.port.PaymentGateway.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Plays the provider's webhook system for the mock gateway (P4.3): after a charge or decline it POSTs a
 * signed event to our own webhook endpoint over real HTTP, after a delay, sometimes twice
 * ({@code duplicate-webhook-rate}), so the idempotent receiver is exercised the way a real provider
 * would exercise it. A TIMEOUT charge sends nothing: that payment is resolved by reconciliation (P4.8).
 */
@Component
class MockWebhookSender {
    private static final Logger log = LoggerFactory.getLogger(MockWebhookSender.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final RestClient http = RestClient.create();
    private final WebhookSignature signature;
    private final ObjectMapper json;
    private final Duration delay;
    private final double duplicateRate;
    private final boolean enabled;
    private volatile String url;

    MockWebhookSender(MockPaymentGateway gateway, WebhookSignature signature, ObjectMapper json,
                      @Value("${payment.mock.webhook-delay:500ms}") Duration delay,
                      @Value("${payment.mock.duplicate-webhook-rate:0.2}") double duplicateRate,
                      @Value("${payment.mock.webhooks-enabled:true}") boolean enabled) {
        this.signature = signature;
        this.json = json;
        this.delay = delay;
        this.duplicateRate = duplicateRate;
        this.enabled = enabled;
        gateway.onCharge(this::charged);
    }

    @EventListener
    void serverStarted(WebServerInitializedEvent event) {
        url = "http://localhost:" + event.getWebServer().getPort() + "/api/v1/payments/webhooks/mock";
    }

    private void charged(String orderId, OrderStatus status) {
        if (!enabled || url == null) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", "evt_" + UUID.randomUUID());
        event.put("type", status == OrderStatus.CAPTURED ? "payment.captured" : "payment.failed");
        event.put("orderId", orderId);
        if (status == OrderStatus.DECLINED) event.put("reason", "card_declined");
        event.put("occurredAt", Instant.now().toString());
        int copies = ThreadLocalRandom.current().nextDouble() < duplicateRate ? 2 : 1;
        for (int i = 0; i < copies; i++) {
            scheduler.schedule(() -> send(event), delay.toMillis() * (i + 1), TimeUnit.MILLISECONDS);
        }
    }

    private void send(Map<String, Object> event) {
        try {
            byte[] body = json.writeValueAsBytes(event);
            http.post().uri(url).contentType(MediaType.APPLICATION_JSON).header("X-Mock-Signature", signature.sign(body))
                    .body(body).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Mock webhook {} not delivered: {}", event.get("eventId"), e.toString());
        }
    }
}
