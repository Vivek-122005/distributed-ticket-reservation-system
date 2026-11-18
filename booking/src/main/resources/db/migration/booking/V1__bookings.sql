-- P3.1/P3.2: booking module schema. Owned by the booking module only (ADR 002): no foreign keys into
-- inventory.*, because after extraction (P7.8) the two live in different databases. hold_id and
-- show_id are references by value, checked through InventoryApi.

CREATE TABLE booking.bookings (
  id              UUID PRIMARY KEY,
  user_id         UUID        NOT NULL,
  show_id         UUID        NOT NULL,
  hold_id         UUID        NOT NULL UNIQUE,   -- one booking per hold
  status          VARCHAR(20) NOT NULL CHECK (status IN
                    ('PENDING_PAYMENT', 'CONFIRMED', 'FAILED', 'EXPIRED', 'CANCELLED', 'REFUND_PENDING', 'REFUNDED')),
  amount_minor    BIGINT      NOT NULL CHECK (amount_minor >= 0),
  currency        CHAR(3)     NOT NULL,
  hold_expires_at TIMESTAMPTZ NOT NULL,
  -- Optimistic locking: a webhook, the expiry path and a cancel may race on one booking (P3.2).
  version         BIGINT      NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Booking history (P3.8): a user's bookings, newest first.
CREATE INDEX idx_bookings_user ON booking.bookings(user_id, created_at DESC);

-- Seat code and price are copied at booking time, so history never calls inventory and a later
-- price change never rewrites what the user paid.
CREATE TABLE booking.booking_seats (
  booking_id  UUID        NOT NULL REFERENCES booking.bookings(id),
  seat_code   VARCHAR(20) NOT NULL,
  price_minor BIGINT      NOT NULL CHECK (price_minor >= 0),
  PRIMARY KEY (booking_id, seat_code)
);
