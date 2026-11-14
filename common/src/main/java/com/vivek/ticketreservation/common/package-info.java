/**
 * Shared library used by every service.
 *
 * <p>Only code that is genuinely cross-cutting belongs here: the Kafka event envelope (P5.1),
 * the idempotency-key interceptor (P3.5), the outbox writer/relay (P5.2-P5.3) and the
 * idempotent consumer base class (P5.5). Domain logic of a single service never goes here,
 * so services stay independently deployable.
 */
package com.vivek.ticketreservation.common;
