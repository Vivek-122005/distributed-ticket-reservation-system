package com.vivek.ticketreservation.booking.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's purchase of the seats of one inventory hold. Aggregate root; framework-free.
 *
 * <p>The booking decides which transitions are legal ({@link BookingStatus}); who triggers them
 * (user, payment webhook, expiry, the saga in P4.4) is decided outside (SRP). Every transition is
 * idempotent: repeating the one that already happened returns {@code false} and changes nothing,
 * so retried webhooks and saga steps are safe. Any other illegal move throws.
 *
 * <p>{@code version} is the optimistic-lock token: the store writes a change only if the row still
 * has the version this object was loaded with (P3.2).
 */
public final class Booking {
    public static final int MAX_SEATS = 8;

    private final UUID id;
    private final UUID userId;
    private final UUID showId;
    private final UUID holdId;
    private final List<BookingSeat> seats;
    private final long amountMinor;
    private final String currency;
    private final Instant holdExpiresAt;
    private final Instant createdAt;
    private final long version;
    private BookingStatus status;

    public Booking(UUID id, UUID userId, UUID showId, UUID holdId, BookingStatus status, List<BookingSeat> seats,
                   long amountMinor, String currency, Instant holdExpiresAt, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.showId = Objects.requireNonNull(showId);
        this.holdId = Objects.requireNonNull(holdId);
        this.status = Objects.requireNonNull(status);
        this.seats = List.copyOf(seats);
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency);
        this.holdExpiresAt = Objects.requireNonNull(holdExpiresAt);
        this.createdAt = createdAt;
        this.version = version;
        if (this.seats.isEmpty() || this.seats.size() > MAX_SEATS) {
            throw new IllegalArgumentException("a booking has 1 to " + MAX_SEATS + " seats");
        }
        long sum = this.seats.stream().mapToLong(BookingSeat::priceMinor).sum();
        if (sum != amountMinor) throw new IllegalArgumentException("amount " + amountMinor + " != sum of seat prices " + sum);
    }

    /** A new booking for a freshly created hold: always {@code PENDING_PAYMENT}, amount = sum of seat prices. */
    public static Booking pending(UUID id, UUID userId, UUID showId, UUID holdId, List<BookingSeat> seats,
                                  String currency, Instant holdExpiresAt) {
        long amount = seats.stream().mapToLong(BookingSeat::priceMinor).sum();
        return new Booking(id, userId, showId, holdId, BookingStatus.PENDING_PAYMENT, seats, amount, currency,
                holdExpiresAt, null, 0);
    }

    public boolean belongsTo(UUID user) { return userId.equals(user); }

    /** Payment captured and seats confirmed. */
    public boolean markConfirmed() { return moveTo(BookingStatus.CONFIRMED); }

    /** Payment declined; the seats are released by the caller. */
    public boolean markFailed() { return moveTo(BookingStatus.FAILED); }

    /** The hold expired before payment. */
    public boolean markExpired() { return moveTo(BookingStatus.EXPIRED); }

    /** The user cancelled while the booking was still pending (P3.7). */
    public boolean cancel() { return moveTo(BookingStatus.CANCELLED); }

    /** Money arrived after the booking was expired or cancelled: it must go back (late-payment path). */
    public boolean requireRefund() { return moveTo(BookingStatus.REFUND_PENDING); }

    public boolean markRefunded() { return moveTo(BookingStatus.REFUNDED); }

    private boolean moveTo(BookingStatus next) {
        if (status == next) return false;
        if (!status.canTransitionTo(next)) throw new IllegalStateTransitionException(id, status, next);
        status = next;
        return true;
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public UUID showId() { return showId; }
    public UUID holdId() { return holdId; }
    public BookingStatus status() { return status; }
    public List<BookingSeat> seats() { return seats; }
    public long amountMinor() { return amountMinor; }
    public String currency() { return currency; }
    public Instant holdExpiresAt() { return holdExpiresAt; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }
}
