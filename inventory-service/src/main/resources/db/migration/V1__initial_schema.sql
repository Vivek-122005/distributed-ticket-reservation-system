CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE events (
  id UUID PRIMARY KEY,
  event_code VARCHAR(80) NOT NULL UNIQUE,
  name VARCHAR(200) NOT NULL,
  status VARCHAR(30) NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE holds (
  id UUID PRIMARY KEY,
  event_id UUID NOT NULL REFERENCES events(id),
  user_id UUID NOT NULL,
  status VARCHAR(30) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE seat_inventory (
  id UUID PRIMARY KEY,
  event_id UUID NOT NULL REFERENCES events(id),
  seat_code VARCHAR(20) NOT NULL,
  status VARCHAR(30) NOT NULL,
  hold_id UUID REFERENCES holds(id),
  held_by_user_id UUID,
  hold_expires_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_event_seat UNIQUE(event_id, seat_code)
);

CREATE INDEX idx_seat_inventory_lookup ON seat_inventory(event_id, status, seat_code);
CREATE INDEX idx_holds_expiry ON holds(status, expires_at);

INSERT INTO events (id, event_code, name, status, starts_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'demo-concert', 'Demo Concert', 'ON_SALE', '2027-01-15T18:00:00Z');

INSERT INTO seat_inventory (id, event_id, seat_code, status)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000001', 'A' || generate_series, 'AVAILABLE'
FROM generate_series(1, 100);
