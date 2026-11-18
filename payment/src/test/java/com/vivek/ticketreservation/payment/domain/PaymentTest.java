package com.vivek.ticketreservation.payment.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.vivek.ticketreservation.payment.domain.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The payment state machine (plan §4.4b) without Spring or a database. */
class PaymentTest {

    private static Payment initiated() {
        return Payment.initiated(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 300_000, "INR", "MOCK", "o1");
    }

    @Test
    void happyPath() {
        Payment p = initiated();
        assertThat(p.startProcessing()).isTrue();
        assertThat(p.markCaptured()).isTrue();
        assertThat(p.markCaptured()).as("repeat is a no-op").isFalse();
        assertThat(p.markRefunded()).isTrue();
        assertThat(p.status()).isEqualTo(REFUNDED);
    }

    @Test
    void unknownResolvesEitherWay() {
        Payment captured = initiated();
        captured.startProcessing();
        captured.markUnknown();
        assertThat(captured.markCaptured()).isTrue();

        Payment failed = initiated();
        failed.startProcessing();
        failed.markUnknown();
        assertThat(failed.markFailed("no_charge")).isTrue();
        assertThat(failed.failureReason()).isEqualTo("no_charge");
    }

    @Test
    void cannotChargeWithoutProcessingOrAfterCancel() {
        assertThatThrownBy(initiated()::markCaptured).isInstanceOf(IllegalPaymentTransitionException.class);
        Payment cancelled = initiated();
        assertThat(cancelled.cancel()).isTrue();
        assertThatThrownBy(cancelled::startProcessing).isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    void aCapturedPaymentIgnoresAStaleDecline() {
        Payment p = initiated();
        p.startProcessing();
        p.markCaptured();
        assertThat(p.canMoveTo(FAILED)).isFalse();
        assertThat(p.canMoveTo(CAPTURED)).as("same state is fine").isTrue();
    }

    @Test
    void onlyAnUnpaidIntentCanBeCancelled() {
        Payment p = initiated();
        p.startProcessing();
        assertThat(p.status().canTransitionTo(CANCELLED)).isFalse();
    }

    @Test
    void terminalStates() {
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(REFUNDED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(CAPTURED.isTerminal()).isFalse();
    }
}
