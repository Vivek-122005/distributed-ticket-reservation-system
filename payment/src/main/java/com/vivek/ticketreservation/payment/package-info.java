/**
 * Payment module: payment intents and their state machine, the {@code PaymentGateway} port with a
 * mock adapter (failure injection), signed webhooks and reconciliation of UNKNOWN payments (P4).
 *
 * <p>Never depends on the booking module; booking reacts to the payment events in {@code payment.api}.
 * Target design: docs/lld/class-diagrams.md#3-payment-module.
 */
package com.vivek.ticketreservation.payment;
