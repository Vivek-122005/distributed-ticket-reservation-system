package com.vivek.ticketreservation.payment;

import com.vivek.ticketreservation.payment.api.PaymentApi;
import com.vivek.ticketreservation.payment.api.PaymentState;
import com.vivek.ticketreservation.payment.api.PaymentView;
import com.vivek.ticketreservation.payment.application.WebhookSignature;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.PaymentEventLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** P4.3: signed, idempotent, out-of-order-safe webhooks. */
@IntegrationTest
@Import(PaymentEventLog.class)
@SuppressWarnings("rawtypes")
class WebhookIT {

    @Autowired PaymentApi payments;
    @Autowired WebhookSignature signature;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentEventLog events;

    private final UUID user = UUID.randomUUID();

    @Test
    void capturedWebhookCapturesAProcessingPaymentOnce() {
        PaymentView p = intentIn("PROCESSING");
        String eventId = "evt_" + UUID.randomUUID();

        ResponseEntity<Map> first = deliver(eventId, "payment.captured", p.gatewayOrderId());
        ResponseEntity<Map> duplicate = deliver(eventId, "payment.captured", p.gatewayOrderId());

        assertThat(first.getBody()).containsEntry("result", "APPLIED");
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(duplicate.getBody()).containsEntry("result", "DUPLICATE");
        assertThat(status(p)).isEqualTo(PaymentState.CAPTURED);
        assertThat(events.captured(p.bookingId())).isEqualTo(1);
    }

    @Test
    void aSecondCaptureEventWithANewIdIsHarmless() {
        PaymentView p = intentIn("PROCESSING");
        deliver("evt_" + UUID.randomUUID(), "payment.captured", p.gatewayOrderId());
        ResponseEntity<Map> again = deliver("evt_" + UUID.randomUUID(), "payment.captured", p.gatewayOrderId());

        assertThat(again.getBody()).containsEntry("result", "APPLIED");
        assertThat(events.captured(p.bookingId())).as("announced once").isEqualTo(1);
    }

    @Test
    void aStaleFailureAfterCaptureIsIgnored() {
        PaymentView p = intentIn("CAPTURED");

        ResponseEntity<Map> response = deliver("evt_" + UUID.randomUUID(), "payment.failed", p.gatewayOrderId());

        assertThat(response.getBody()).containsEntry("result", "IGNORED");
        assertThat(status(p)).isEqualTo(PaymentState.CAPTURED);
        assertThat(events.failed(p.bookingId())).isZero();
    }

    @Test
    void aWebhookResolvesAnUnknownPayment() {
        PaymentView p = intentIn("UNKNOWN");
        deliver("evt_" + UUID.randomUUID(), "payment.captured", p.gatewayOrderId());
        assertThat(status(p)).isEqualTo(PaymentState.CAPTURED);
    }

    @Test
    void aForgedWebhookIsRejectedAndChangesNothing() {
        PaymentView p = intentIn("PROCESSING");
        byte[] body = body("evt_" + UUID.randomUUID(), "payment.captured", p.gatewayOrderId());

        ResponseEntity<Map> response = post(body, "0".repeat(64));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(p)).isEqualTo(PaymentState.PROCESSING);
    }

    @Test
    void theMockProviderSendsASignedWebhookAfterCheckout() throws InterruptedException {
        PaymentView p = payments.createIntent(UUID.randomUUID(), user, 150_000, "INR");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", user.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        http.postForEntity("/api/v1/payments/" + p.paymentId() + "/checkout", new HttpEntity<>("{\"instruction\":\"SUCCESS\"}", headers), Map.class);

        Integer received = 0;
        for (int i = 0; i < 50 && received == 0; i++) {
            Thread.sleep(50);
            received = jdbc.queryForObject("SELECT count(*) FROM payment.webhook_events WHERE order_id = ?", Integer.class, p.gatewayOrderId());
        }
        assertThat(received).as("the provider's webhook arrived over HTTP with a valid signature").isEqualTo(1);
        assertThat(events.captured(p.bookingId())).as("checkout and webhook both learned it; announced once").isEqualTo(1);
    }

    private PaymentView intentIn(String status) {
        PaymentView p = payments.createIntent(UUID.randomUUID(), user, 150_000, "INR");
        jdbc.update("UPDATE payment.payments SET status = ? WHERE id = ?", status, p.paymentId());
        return p;
    }

    private PaymentState status(PaymentView p) {
        return payments.findByBooking(p.bookingId()).orElseThrow().status();
    }

    private ResponseEntity<Map> deliver(String eventId, String type, String orderId) {
        byte[] body = body(eventId, type, orderId);
        return post(body, signature.sign(body));
    }

    private static byte[] body(String eventId, String type, String orderId) {
        return ("{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"orderId\":\"" + orderId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private ResponseEntity<Map> post(byte[] body, String sig) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Mock-Signature", sig);
        return http.postForEntity("/api/v1/payments/webhooks/mock", new HttpEntity<>(body, headers), Map.class);
    }
}
