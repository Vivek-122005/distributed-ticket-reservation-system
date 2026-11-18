package com.vivek.ticketreservation.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivek.ticketreservation.payment.application.port.PaymentStore;
import com.vivek.ticketreservation.payment.application.port.WebhookInbox;
import com.vivek.ticketreservation.payment.domain.Payment;
import com.vivek.ticketreservation.payment.domain.PaymentStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

/**
 * Receives a provider webhook (P4.3): verify the signature, drop duplicates, apply the outcome unless
 * it is stale.
 *
 * <p>Pattern: Idempotent Receiver (docs/lld/DESIGN_PATTERNS.md#12). Three layers make at-least-once
 * delivery harmless: the event id is recorded in {@code payment.webhook_events}; the transition is
 * idempotent (a second "captured" is a no-op); and a stale event (a "failed" arriving after the payment
 * was captured, or anything for a refunded payment) is recorded as IGNORED instead of applied. The
 * outcome is applied <b>before</b> the event id is recorded, so a crash in between makes the provider's
 * retry apply it again (harmless) rather than drop it.
 */
@Service
public class HandleWebhookUseCase {

    public enum Result { APPLIED, IGNORED, DUPLICATE, BAD_SIGNATURE, MALFORMED }

    private final WebhookSignature signature;
    private final ObjectMapper json;
    private final WebhookInbox inbox;
    private final PaymentStore store;
    private final PaymentTransitions transitions;

    public HandleWebhookUseCase(WebhookSignature signature, ObjectMapper json, WebhookInbox inbox, PaymentStore store,
                                PaymentTransitions transitions) {
        this.signature = signature;
        this.json = json;
        this.inbox = inbox;
        this.store = store;
        this.transitions = transitions;
    }

    public Result handle(byte[] body, String signatureHeader) {
        if (!signature.verify(body, signatureHeader)) return Result.BAD_SIGNATURE;
        JsonNode event;
        try { event = json.readTree(body); } catch (IOException e) { return Result.MALFORMED; }
        String eventId = event.path("eventId").asText(null);
        String type = event.path("type").asText(null);
        String orderId = event.path("orderId").asText(null);
        if (eventId == null || type == null || orderId == null) return Result.MALFORMED;
        if (inbox.seen(eventId)) return Result.DUPLICATE;

        Optional<Payment> payment = store.findByOrder(orderId);
        Result result = payment.isEmpty() ? Result.IGNORED : apply(payment.get(), type, event.path("reason").asText("declined"));
        inbox.record(eventId, orderId, type, result.name());
        return result;
    }

    private Result apply(Payment payment, String type, String reason) {
        PaymentStatus target = switch (type) {
            case "payment.captured" -> PaymentStatus.CAPTURED;
            case "payment.failed" -> PaymentStatus.FAILED;
            default -> null;
        };
        if (target == null) return Result.IGNORED;
        PaymentTransitions.Applied applied = transitions.apply(payment.id(), p -> p.canMoveTo(target)
                && (target == PaymentStatus.CAPTURED ? p.markCaptured() : p.markFailed(reason)));
        return applied.payment().status() == target ? Result.APPLIED : Result.IGNORED;
    }
}
