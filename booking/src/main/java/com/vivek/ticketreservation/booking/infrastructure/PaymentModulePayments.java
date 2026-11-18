package com.vivek.ticketreservation.booking.infrastructure;

import com.vivek.ticketreservation.booking.application.port.Payments;
import com.vivek.ticketreservation.payment.api.PaymentApi;
import com.vivek.ticketreservation.payment.api.PaymentView;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * In-process adapter from booking's {@link Payments} port to {@link PaymentApi} (Adapter,
 * docs/lld/DESIGN_PATTERNS.md#8-adapter). Replaced by an HTTP adapter when payment is extracted (P7.9).
 */
@Component
class PaymentModulePayments implements Payments {
    private final PaymentApi payments;

    PaymentModulePayments(PaymentApi payments) { this.payments = payments; }

    @Override
    public PaymentInfo createIntent(UUID bookingId, UUID userId, long amountMinor, String currency) {
        return info(payments.createIntent(bookingId, userId, amountMinor, currency));
    }

    @Override
    public Optional<PaymentInfo> find(UUID bookingId) { return payments.findByBooking(bookingId).map(PaymentModulePayments::info); }

    @Override
    public Map<UUID, PaymentInfo> findAll(Collection<UUID> bookingIds) {
        return payments.findByBookings(bookingIds).stream().collect(Collectors.toMap(PaymentView::bookingId, PaymentModulePayments::info));
    }

    @Override
    public Status cancel(UUID bookingId) { return Status.valueOf(payments.cancel(bookingId).status().name()); }

    @Override
    public void refund(UUID bookingId) { payments.refund(bookingId); }

    private static PaymentInfo info(PaymentView v) {
        return new PaymentInfo(v.paymentId(), Status.valueOf(v.status().name()), v.gateway(), v.gatewayOrderId());
    }
}
