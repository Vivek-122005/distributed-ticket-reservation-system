package com.vivek.ticketreservation.support;

import com.vivek.ticketreservation.payment.api.PaymentCaptured;
import com.vivek.ticketreservation.payment.api.PaymentFailed;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records payment events from any thread (request threads included); import it into a test context. */
@TestComponent
public class PaymentEventLog {
    private final List<Object> events = new CopyOnWriteArrayList<>();

    @EventListener void on(PaymentCaptured e) { events.add(e); }
    @EventListener void on(PaymentFailed e) { events.add(e); }

    public long captured(UUID bookingId) {
        return events.stream().filter(e -> e instanceof PaymentCaptured c && c.bookingId().equals(bookingId)).count();
    }

    public long failed(UUID bookingId) {
        return events.stream().filter(e -> e instanceof PaymentFailed f && f.bookingId().equals(bookingId)).count();
    }
}
