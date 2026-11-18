-- P4.3: every gateway webhook we accepted, by the provider's event id (idempotent receiver).
-- Providers deliver at least once, so the same event id can arrive many times.
CREATE TABLE payment.webhook_events (
  event_id    VARCHAR(80) PRIMARY KEY,
  order_id    VARCHAR(64) NOT NULL,
  type        VARCHAR(40) NOT NULL,
  result      VARCHAR(12) NOT NULL CHECK (result IN ('APPLIED', 'IGNORED')),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
