package com.vivek.ticketreservation.payment.web;

import com.vivek.ticketreservation.payment.application.HandleWebhookUseCase;
import com.vivek.ticketreservation.payment.application.HandleWebhookUseCase.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Provider callbacks (P4.3). Takes the raw body, because the signature is over the exact bytes. Answers
 * 2xx for anything handled, including duplicates and stale events, so the provider stops retrying; 401
 * for a bad signature; 400 for a malformed event. No user header: the idempotency filter lets it through.
 */
@RestController
@RequestMapping("/api/v1/payments/webhooks")
public class WebhookController {
    private final HandleWebhookUseCase handler;

    public WebhookController(HandleWebhookUseCase handler) { this.handler = handler; }

    @PostMapping("/mock")
    public ResponseEntity<Map<String, String>> mock(@RequestBody byte[] body,
                                                    @RequestHeader(value = "X-Mock-Signature", required = false) String signature) {
        Result result = handler.handle(body, signature);
        HttpStatus status = switch (result) {
            case BAD_SIGNATURE -> HttpStatus.UNAUTHORIZED;
            case MALFORMED -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(Map.of("result", result.name()));
    }
}
