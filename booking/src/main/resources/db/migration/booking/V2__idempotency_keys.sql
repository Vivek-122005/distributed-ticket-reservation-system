-- P3.4: idempotency keys of the booking API, owned by the booking module.
-- Scoped per user: two users can never collide on a key or read each other's stored answers.
CREATE TABLE booking.idempotency_keys (
  user_id               UUID         NOT NULL,
  idem_key              VARCHAR(100) NOT NULL,
  request_hash          CHAR(64)     NOT NULL,   -- SHA-256 of method, path and body
  status                VARCHAR(12)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
  response_status       INT,
  response_content_type VARCHAR(100),
  response_location     VARCHAR(300),
  response_body         BYTEA,
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  expires_at            TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (user_id, idem_key),
  CONSTRAINT ck_completed_has_response CHECK (status <> 'COMPLETED' OR response_status IS NOT NULL)
);
CREATE INDEX idx_idempotency_expiry ON booking.idempotency_keys(expires_at);
