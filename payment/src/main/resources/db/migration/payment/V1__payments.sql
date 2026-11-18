-- P4.1/P4.2: payment module schema, owned by the payment module (own Flyway history, like booking).

CREATE TABLE payment.payments (
  id               UUID PRIMARY KEY,
  -- Invariant: at most one payment per booking. This, plus the gateway idempotency key = booking id,
  -- is what prevents a double charge (N2).
  booking_id       UUID        NOT NULL UNIQUE,
  user_id          UUID        NOT NULL,
  amount_minor     BIGINT      NOT NULL CHECK (amount_minor >= 0),
  currency         CHAR(3)     NOT NULL,
  status           VARCHAR(12) NOT NULL CHECK (status IN
                     ('INITIATED', 'PROCESSING', 'CAPTURED', 'FAILED', 'UNKNOWN', 'REFUNDED', 'CANCELLED')),
  gateway          VARCHAR(12) NOT NULL,
  gateway_order_id VARCHAR(64) NOT NULL UNIQUE,
  failure_reason   VARCHAR(200),
  version          BIGINT      NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Reconciliation (P4.8) scans payments stuck in PROCESSING / UNKNOWN.
CREATE INDEX idx_payments_unresolved ON payment.payments(updated_at) WHERE status IN ('PROCESSING', 'UNKNOWN');

-- The shared idempotency filter (P3.5) in front of checkout, with payment's own keys.
CREATE TABLE payment.idempotency_keys (
  user_id               UUID         NOT NULL,
  idem_key              VARCHAR(100) NOT NULL,
  request_hash          CHAR(64)     NOT NULL,
  status                VARCHAR(12)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
  response_status       INT,
  response_content_type VARCHAR(100),
  response_location     VARCHAR(300),
  response_body         BYTEA,
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  expires_at            TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (user_id, idem_key)
);
