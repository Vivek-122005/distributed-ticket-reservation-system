package com.vivek.ticketreservation.payment.infrastructure;

import com.vivek.ticketreservation.payment.application.WebhookSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The shared webhook secret (one per provider in production; from the environment, never committed for real). */
@Configuration
class WebhookConfig {
    @Bean
    WebhookSignature webhookSignature(@Value("${payment.webhook.secret:local-dev-webhook-secret}") String secret) {
        return new WebhookSignature(secret);
    }
}
