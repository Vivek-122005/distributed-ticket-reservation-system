-- P4.4: persisted state of the booking saga, one row per booking, written in the same transaction as
-- the booking. The step is saved BEFORE it is executed, so after a crash the recovery job knows exactly
-- which step to repeat (every step is idempotent).
CREATE TABLE booking.booking_sagas (
  booking_id    UUID        PRIMARY KEY REFERENCES booking.bookings(id),
  step          VARCHAR(20) NOT NULL CHECK (step IN
                  ('AWAITING_PAYMENT', 'CONFIRMING_SEATS', 'RELEASING_SEATS', 'REFUNDING', 'DONE')),
  attempts      INT         NOT NULL DEFAULT 0,
  -- When the saga must be looked at again: a payment check, the hold's expiry, or a retry after failure.
  next_retry_at TIMESTAMPTZ NOT NULL,
  last_error    VARCHAR(500),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sagas_due ON booking.booking_sagas(next_retry_at) WHERE step <> 'DONE';
