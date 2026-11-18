package com.vivek.ticketreservation.payment.application;

import com.vivek.ticketreservation.payment.api.PaymentNotFoundException;
import com.vivek.ticketreservation.payment.api.PaymentView;
import com.vivek.ticketreservation.payment.application.port.PaymentGateway;
import com.vivek.ticketreservation.payment.application.port.PaymentGateway.ChargeResult;
import com.vivek.ticketreservation.payment.application.port.PaymentGateway.GatewayTimeoutException;
import com.vivek.ticketreservation.payment.application.port.PaymentStore;
import com.vivek.ticketreservation.payment.domain.IllegalPaymentTransitionException;
import com.vivek.ticketreservation.payment.domain.Payment;
import com.vivek.ticketreservation.payment.domain.PaymentStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The user pays (mock checkout page, P4.1): {@code INITIATED → PROCESSING}, call the gateway, then
 * {@code CAPTURED}, {@code FAILED} or, if the gateway did not answer, {@code UNKNOWN}.
 *
 * <p>{@code PROCESSING} is committed <b>before</b> the gateway call, and no transaction is open during
 * it: a slow provider never holds a database connection, and a crash mid-call leaves a
 * {@code PROCESSING} row that reconciliation (P4.8) resolves. Paying a payment that is already past
 * {@code INITIATED} returns it unchanged (a second click never charges twice).
 */
@Service
public class CheckoutUseCase {
    private final PaymentStore store;
    private final PaymentGateway gateway;
    private final PaymentTransitions transitions;

    public CheckoutUseCase(PaymentStore store, PaymentGateway gateway, PaymentTransitions transitions) {
        this.store = store;
        this.gateway = gateway;
        this.transitions = transitions;
    }

    public PaymentView execute(UUID paymentId, UUID userId, String instruction) {
        Payment payment = store.find(paymentId).filter(p -> p.belongsTo(userId))
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.status() == PaymentStatus.CANCELLED) {
            throw new IllegalPaymentTransitionException(paymentId, payment.status(), PaymentStatus.PROCESSING);
        }
        if (payment.status() != PaymentStatus.INITIATED) return PaymentViews.of(payment);

        if (!transitions.apply(paymentId, p -> p.status() == PaymentStatus.INITIATED && p.startProcessing()).changed()) {
            return PaymentViews.of(store.find(paymentId).orElseThrow());   // a concurrent click started it
        }
        try {
            ChargeResult result = gateway.charge(payment.gatewayOrderId(), instruction);
            return PaymentViews.of(transitions.apply(paymentId, p -> result.captured()
                    ? p.canMoveTo(PaymentStatus.CAPTURED) && p.markCaptured()
                    : p.canMoveTo(PaymentStatus.FAILED) && p.markFailed(result.declineReason())).payment());
        } catch (GatewayTimeoutException timeout) {
            return PaymentViews.of(transitions.apply(paymentId,
                    p -> p.status() == PaymentStatus.PROCESSING && p.markUnknown()).payment());
        }
    }

    public PaymentView get(UUID paymentId, UUID userId) {
        return store.find(paymentId).filter(p -> p.belongsTo(userId)).map(PaymentViews::of)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
