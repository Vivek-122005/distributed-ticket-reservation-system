package com.vivek.ticketreservation.payment.web;

import com.vivek.ticketreservation.payment.api.PaymentView;
import com.vivek.ticketreservation.payment.application.CheckoutUseCase;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The mock checkout: what the provider's payment page would do (P4.1). {@code instruction} picks the
 * outcome (SUCCESS, DECLINE, TIMEOUT, RANDOM) so every saga path can be demonstrated.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private static final String USER = "X-User-Id";
    private final CheckoutUseCase checkout;

    public PaymentController(CheckoutUseCase checkout) { this.checkout = checkout; }

    public record CheckoutRequest(String instruction) { }

    @GetMapping("/{paymentId}")
    public PaymentView get(@PathVariable UUID paymentId, @RequestHeader(USER) UUID userId) {
        return checkout.get(paymentId, userId);
    }

    @PostMapping("/{paymentId}/checkout")
    public PaymentView pay(@PathVariable UUID paymentId, @RequestHeader(USER) UUID userId,
                           @RequestBody(required = false) CheckoutRequest request) {
        return checkout.execute(paymentId, userId, request == null ? null : request.instruction());
    }
}
