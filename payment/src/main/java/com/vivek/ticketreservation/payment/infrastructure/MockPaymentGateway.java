package com.vivek.ticketreservation.payment.infrastructure;

import com.vivek.ticketreservation.payment.application.port.PaymentGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A fake payment provider with failure injection, so every saga path can be shown on demand (P4.1).
 *
 * <p>Instructions: {@code SUCCESS} captures, {@code DECLINE} declines, {@code TIMEOUT} <b>captures the
 * money but loses the answer</b> (the hard case: the payment becomes UNKNOWN and only reconciliation
 * finds out), {@code RANDOM} uses the configured decline and timeout rates. Orders live in memory: a
 * restart forgets them, and {@link #fetchStatus} then answers NOT_FOUND, which reconciliation treats as
 * "no money moved". Pattern: Adapter (docs/lld/DESIGN_PATTERNS.md#8-adapter).
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    /** What the mock provider remembers about an order. */
    private static final class Order {
        final String id;
        volatile OrderStatus status = OrderStatus.NOT_CHARGED;
        Order(String id) { this.id = id; }
    }

    private final Map<String, Order> ordersByKey = new ConcurrentHashMap<>();
    private final Map<String, Order> ordersById = new ConcurrentHashMap<>();
    private final Duration latency;
    private final double declineRate;
    private final double timeoutRate;
    private volatile ChargeListener listener = (orderId, status) -> { };

    public MockPaymentGateway(@Value("${payment.mock.latency:300ms}") Duration latency,
                              @Value("${payment.mock.decline-rate:0.1}") double declineRate,
                              @Value("${payment.mock.timeout-rate:0.05}") double timeoutRate) {
        this.latency = latency;
        this.declineRate = declineRate;
        this.timeoutRate = timeoutRate;
    }

    /** Notified whenever an order is charged or declined (the provider's webhooks hook in here, P4.3). */
    public interface ChargeListener { void charged(String orderId, OrderStatus status); }

    public void onCharge(ChargeListener listener) { this.listener = listener; }

    @Override public String name() { return "MOCK"; }

    @Override
    public String createOrder(String idempotencyKey, long amountMinor, String currency) {
        Order order = ordersByKey.computeIfAbsent(idempotencyKey,
                k -> new Order("mock_order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)));
        ordersById.putIfAbsent(order.id, order);
        return order.id;
    }

    @Override
    public ChargeResult charge(String orderId, String instruction) {
        Order order = order(orderId);
        pause();
        synchronized (order) {
            if (order.status == OrderStatus.CAPTURED) return ChargeResult.capturedResult();   // idempotent
            if (order.status == OrderStatus.DECLINED) return ChargeResult.declined("card_declined");
            String outcome = resolve(instruction);
            switch (outcome) {
                case "DECLINE" -> {
                    order.status = OrderStatus.DECLINED;
                    listener.charged(orderId, OrderStatus.DECLINED);
                    return ChargeResult.declined("card_declined");
                }
                case "TIMEOUT" -> {
                    order.status = OrderStatus.CAPTURED;   // the money moved...
                    throw new GatewayTimeoutException("mock gateway did not answer for " + orderId);   // ...the answer did not
                }
                default -> {
                    order.status = OrderStatus.CAPTURED;
                    listener.charged(orderId, OrderStatus.CAPTURED);
                    return ChargeResult.capturedResult();
                }
            }
        }
    }

    @Override
    public void refund(String orderId) {
        Order order = order(orderId);
        synchronized (order) {
            if (order.status == OrderStatus.CAPTURED) order.status = OrderStatus.REFUNDED;
            else if (order.status != OrderStatus.REFUNDED) throw new IllegalStateException("order " + orderId + " is " + order.status);
        }
    }

    @Override
    public OrderStatus fetchStatus(String orderId) {
        Order order = ordersById.get(orderId);
        return order == null ? OrderStatus.NOT_FOUND : order.status;
    }

    private Order order(String orderId) {
        Order order = ordersById.get(orderId);
        if (order == null) throw new IllegalArgumentException("unknown order " + orderId);
        return order;
    }

    private String resolve(String instruction) {
        String value = instruction == null ? "SUCCESS" : instruction.toUpperCase();
        if (!value.equals("RANDOM")) return value;
        double roll = ThreadLocalRandom.current().nextDouble();
        return roll < declineRate ? "DECLINE" : roll < declineRate + timeoutRate ? "TIMEOUT" : "SUCCESS";
    }

    private void pause() {
        if (latency.isZero()) return;
        try { Thread.sleep(latency); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
