package com.vivek.ticketreservation.payment;

import com.vivek.ticketreservation.payment.api.PaymentApi;
import com.vivek.ticketreservation.payment.api.PaymentNotRefundableException;
import com.vivek.ticketreservation.payment.api.PaymentState;
import com.vivek.ticketreservation.payment.api.PaymentView;
import com.vivek.ticketreservation.payment.application.port.PaymentGateway;
import com.vivek.ticketreservation.payment.application.port.PaymentGateway.OrderStatus;
import com.vivek.ticketreservation.support.IntegrationTest;
import com.vivek.ticketreservation.support.PaymentEventLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P4.1–P4.2: payment intents, the mock checkout and the payment state machine against real Postgres. */
@IntegrationTest
@Import(PaymentEventLog.class)
@SuppressWarnings("rawtypes")
class PaymentIT {

    @Autowired PaymentApi payments;
    @Autowired PaymentGateway gateway;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentEventLog events;

    private final UUID user = UUID.randomUUID();
    private UUID booking;

    @BeforeEach
    void newBooking() { booking = UUID.randomUUID(); }

    @Test
    void oneIntentPerBooking() {
        PaymentView first = payments.createIntent(booking, user, 300_000, "INR");
        PaymentView again = payments.createIntent(booking, user, 300_000, "INR");

        assertThat(again.paymentId()).isEqualTo(first.paymentId());
        assertThat(first.status()).isEqualTo(PaymentState.INITIATED);
        assertThat(first.gateway()).isEqualTo("MOCK");
        assertThat(first.gatewayOrderId()).startsWith("mock_order_");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payments WHERE booking_id = ?", Long.class, booking)).isEqualTo(1);
    }

    @Test
    void successfulCheckoutCapturesAndAnnouncesItOnce() {
        PaymentView intent = payments.createIntent(booking, user, 150_000, "INR");

        ResponseEntity<Map> paid = checkout(intent.paymentId(), user, "SUCCESS");
        ResponseEntity<Map> clickedAgain = checkout(intent.paymentId(), user, "SUCCESS");

        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paid.getBody()).containsEntry("status", "CAPTURED");
        assertThat(clickedAgain.getBody()).containsEntry("status", "CAPTURED");
        assertThat(events.captured(booking)).isEqualTo(1);
        assertThat(gateway.fetchStatus(intent.gatewayOrderId())).isEqualTo(OrderStatus.CAPTURED);
    }

    @Test
    void declineFailsThePayment() {
        PaymentView intent = payments.createIntent(booking, user, 150_000, "INR");

        ResponseEntity<Map> response = checkout(intent.paymentId(), user, "DECLINE");

        assertThat(response.getBody()).containsEntry("status", "FAILED").containsEntry("failureReason", "card_declined");
        assertThat(events.failed(booking)).isEqualTo(1);
    }

    @Test
    void aLostGatewayAnswerIsUnknownNotFailed() {
        PaymentView intent = payments.createIntent(booking, user, 150_000, "INR");

        ResponseEntity<Map> response = checkout(intent.paymentId(), user, "TIMEOUT");

        assertThat(response.getBody()).containsEntry("status", "UNKNOWN");
        assertThat(gateway.fetchStatus(intent.gatewayOrderId())).as("the money did move").isEqualTo(OrderStatus.CAPTURED);
        assertThat(events.captured(booking)).isZero();
    }

    @Test
    void aCancelledIntentCannotBePaid() {
        PaymentView intent = payments.createIntent(booking, user, 150_000, "INR");

        assertThat(payments.cancel(booking).status()).isEqualTo(PaymentState.CANCELLED);
        assertThat(payments.cancel(booking).status()).as("idempotent").isEqualTo(PaymentState.CANCELLED);

        ResponseEntity<Map> response = checkout(intent.paymentId(), user, "SUCCESS");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(gateway.fetchStatus(intent.gatewayOrderId())).isEqualTo(OrderStatus.NOT_CHARGED);
    }

    @Test
    void aPaymentInFlightIsNotCancelled() {
        PaymentView intent = payments.createIntent(booking, user, 150_000, "INR");
        checkout(intent.paymentId(), user, "SUCCESS");

        assertThat(payments.cancel(booking).status()).as("captured: the refund path handles it").isEqualTo(PaymentState.CAPTURED);
    }

    @Test
    void refundOnlyACapturedPayment() {
        PaymentView intent = payments.createIntent(booking, user, 150_000, "INR");
        assertThatThrownBy(() -> payments.refund(booking)).isInstanceOf(PaymentNotRefundableException.class);

        checkout(intent.paymentId(), user, "SUCCESS");
        assertThat(payments.refund(booking).status()).isEqualTo(PaymentState.REFUNDED);
        assertThat(payments.refund(booking).status()).as("idempotent").isEqualTo(PaymentState.REFUNDED);
        assertThat(gateway.fetchStatus(intent.gatewayOrderId())).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void anotherUserCannotSeeOrPay() {
        PaymentView intent = payments.createIntent(booking, user, 150_000, "INR");
        UUID stranger = UUID.randomUUID();

        assertThat(checkout(intent.paymentId(), stranger, "SUCCESS").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", stranger.toString());
        assertThat(http.exchange("/api/v1/payments/" + intent.paymentId(), HttpMethod.GET, new HttpEntity<>(headers), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void checkoutNeedsAnIdempotencyKey() {
        PaymentView intent = payments.createIntent(booking, user, 150_000, "INR");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", user.toString());
        ResponseEntity<Map> response = http.postForEntity("/api/v1/payments/" + intent.paymentId() + "/checkout",
                new HttpEntity<>("{\"instruction\":\"SUCCESS\"}", headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map> checkout(UUID paymentId, UUID who, String instruction) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", who.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.postForEntity("/api/v1/payments/" + paymentId + "/checkout",
                new HttpEntity<>("{\"instruction\":\"" + instruction + "\"}", headers), Map.class);
    }
}
