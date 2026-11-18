package com.vivek.ticketreservation.payment.application.port;

/**
 * A payment service provider, in the payment module's words (P4.1).
 *
 * <p>Pattern: Adapter (docs/lld/DESIGN_PATTERNS.md#8-adapter). {@code infrastructure.MockPaymentGateway}
 * implements it with failure injection; a Razorpay test-mode adapter would implement the same port.
 * Every call is idempotent at the provider: orders are created with an idempotency key, and charging,
 * refunding or asking about an order twice has no second effect.
 */
public interface PaymentGateway {

    String name();

    /** Creates (or returns the existing) order for this idempotency key. @return the provider's order id */
    String createOrder(String idempotencyKey, long amountMinor, String currency);

    /**
     * Charges the order. {@code instruction} stands in for what the user typed on the provider's page
     * (for the mock: SUCCESS, DECLINE, TIMEOUT or RANDOM).
     * @throws GatewayTimeoutException if no answer came back; the money may or may not have moved
     */
    ChargeResult charge(String orderId, String instruction);

    /** Refunds a captured order. Idempotent. */
    void refund(String orderId);

    /** What the provider knows about the order: the source of truth for reconciliation (P4.8). */
    OrderStatus fetchStatus(String orderId);

    record ChargeResult(boolean captured, String declineReason) {
        public static ChargeResult capturedResult() { return new ChargeResult(true, null); }
        public static ChargeResult declined(String reason) { return new ChargeResult(false, reason); }
    }

    enum OrderStatus { NOT_CHARGED, CAPTURED, DECLINED, REFUNDED, NOT_FOUND }

    class GatewayTimeoutException extends RuntimeException {
        public GatewayTimeoutException(String message) { super(message); }
    }
}
