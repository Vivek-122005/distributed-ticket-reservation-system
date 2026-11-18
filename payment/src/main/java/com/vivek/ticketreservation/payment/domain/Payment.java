package com.vivek.ticketreservation.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The payment for one booking. Aggregate root; framework-free.
 *
 * <p>Every transition is idempotent: repeating the one that already happened returns {@code false}.
 * Callers that may see events out of order (webhooks, reconciliation) ask {@link #canMoveTo} first
 * and ignore stale ones instead of failing (P4.3).
 */
public final class Payment {
    private final UUID id;
    private final UUID bookingId;
    private final UUID userId;
    private final long amountMinor;
    private final String currency;
    private final String gateway;
    private final String gatewayOrderId;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;
    private PaymentStatus status;
    private String failureReason;

    public Payment(UUID id, UUID bookingId, UUID userId, long amountMinor, String currency, PaymentStatus status,
                   String gateway, String gatewayOrderId, String failureReason, Instant createdAt, Instant updatedAt,
                   long version) {
        this.id = Objects.requireNonNull(id);
        this.bookingId = Objects.requireNonNull(bookingId);
        this.userId = Objects.requireNonNull(userId);
        if (amountMinor < 0) throw new IllegalArgumentException("amount must not be negative");
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency);
        this.status = Objects.requireNonNull(status);
        this.gateway = Objects.requireNonNull(gateway);
        this.gatewayOrderId = Objects.requireNonNull(gatewayOrderId);
        this.failureReason = failureReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Payment initiated(UUID id, UUID bookingId, UUID userId, long amountMinor, String currency,
                                    String gateway, String gatewayOrderId) {
        return new Payment(id, bookingId, userId, amountMinor, currency, PaymentStatus.INITIATED, gateway,
                gatewayOrderId, null, null, null, 0);
    }

    public boolean belongsTo(UUID user) { return userId.equals(user); }

    /** True if {@code next} is the current status or a legal next one. */
    public boolean canMoveTo(PaymentStatus next) { return status == next || status.canTransitionTo(next); }

    /** The user submitted payment details; the gateway call is about to start. */
    public boolean startProcessing() { return moveTo(PaymentStatus.PROCESSING); }

    public boolean markCaptured() { return moveTo(PaymentStatus.CAPTURED); }

    public boolean markFailed(String reason) {
        boolean changed = moveTo(PaymentStatus.FAILED);
        if (changed) failureReason = reason;
        return changed;
    }

    /** The gateway did not answer: reconciliation (P4.8) finds out what really happened. */
    public boolean markUnknown() { return moveTo(PaymentStatus.UNKNOWN); }

    public boolean markRefunded() { return moveTo(PaymentStatus.REFUNDED); }

    /** Withdraw an intent nobody paid yet (booking cancelled or expired). */
    public boolean cancel() { return moveTo(PaymentStatus.CANCELLED); }

    private boolean moveTo(PaymentStatus next) {
        if (status == next) return false;
        if (!status.canTransitionTo(next)) throw new IllegalPaymentTransitionException(id, status, next);
        status = next;
        return true;
    }

    public UUID id() { return id; }
    public UUID bookingId() { return bookingId; }
    public UUID userId() { return userId; }
    public long amountMinor() { return amountMinor; }
    public String currency() { return currency; }
    public PaymentStatus status() { return status; }
    public String gateway() { return gateway; }
    public String gatewayOrderId() { return gatewayOrderId; }
    public String failureReason() { return failureReason; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
