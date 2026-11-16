-- P1.0: Show-based inventory model in the module's own schema (plan §4.6, HLD §4.1).
--
-- Catalog:   venues -> venue_sections -> seats   (physical layout, written once per venue)
--            events -> shows                       (what is on, when and where)
-- Inventory: show_seats                            (sellable copy of every seat PER SHOW; the unit of locking)
--
-- The V1 tables (public.events / holds / seat_inventory) only held demo data, so they are dropped
-- instead of migrated. Nothing outside this module reads them.

CREATE SCHEMA IF NOT EXISTS inventory;

-- ---------------------------------------------------------------- catalog
CREATE TABLE inventory.venues (
  id         UUID PRIMARY KEY,
  name       VARCHAR(120) NOT NULL,
  city       VARCHAR(80)  NOT NULL,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE inventory.venue_sections (
  id                  UUID PRIMARY KEY,
  venue_id            UUID        NOT NULL REFERENCES inventory.venues(id),
  code                VARCHAR(20) NOT NULL,
  default_price_minor BIGINT      NOT NULL CHECK (default_price_minor >= 0),
  CONSTRAINT uq_section_code UNIQUE (venue_id, code)
);

CREATE TABLE inventory.seats (
  id         UUID PRIMARY KEY,
  section_id UUID        NOT NULL REFERENCES inventory.venue_sections(id),
  seat_code  VARCHAR(20) NOT NULL,
  row_no     INT         NOT NULL CHECK (row_no > 0),
  col_no     INT         NOT NULL CHECK (col_no > 0),
  CONSTRAINT uq_section_seat UNIQUE (section_id, seat_code)
);

CREATE TABLE inventory.events (
  id          UUID PRIMARY KEY,
  event_code  VARCHAR(40)  NOT NULL UNIQUE,
  name        VARCHAR(200) NOT NULL,
  description VARCHAR(2000),
  status      VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE inventory.shows (
  id            UUID PRIMARY KEY,
  event_id      UUID        NOT NULL REFERENCES inventory.events(id),
  venue_id      UUID        NOT NULL REFERENCES inventory.venues(id),
  starts_at     TIMESTAMPTZ NOT NULL,
  sale_opens_at TIMESTAMPTZ NOT NULL,
  status        VARCHAR(20) NOT NULL CHECK (status IN ('SCHEDULED', 'ON_SALE', 'SOLD_OUT', 'CLOSED')),
  currency      CHAR(3)     NOT NULL DEFAULT 'INR',
  CONSTRAINT ck_sale_before_start CHECK (sale_opens_at <= starts_at)
);
CREATE INDEX idx_shows_event ON inventory.shows(event_id, starts_at);

-- ---------------------------------------------------------------- inventory
-- Stored hold status uses the API vocabulary (HELD, not ACTIVE): SOLID.md deviation #5.
CREATE TABLE inventory.holds (
  id         UUID PRIMARY KEY,
  show_id    UUID        NOT NULL REFERENCES inventory.shows(id),
  user_id    UUID        NOT NULL,
  status     VARCHAR(20) NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_holds_expiry ON inventory.holds(status, expires_at);

-- seat_code, section_code and price are copied from the catalog when the show is created, so the
-- hot claim query touches one table and needs no join (HLD §4.1).
CREATE TABLE inventory.show_seats (
  id              UUID PRIMARY KEY,
  show_id         UUID        NOT NULL REFERENCES inventory.shows(id),
  seat_id         UUID        NOT NULL REFERENCES inventory.seats(id),
  seat_code       VARCHAR(20) NOT NULL,
  section_code    VARCHAR(20) NOT NULL,
  status          VARCHAR(20) NOT NULL CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
  hold_id         UUID REFERENCES inventory.holds(id),
  held_by_user_id UUID,
  hold_expires_at TIMESTAMPTZ,
  price_minor     BIGINT      NOT NULL CHECK (price_minor >= 0),
  version         BIGINT      NOT NULL DEFAULT 0,
  -- Invariant 1: a physical seat exists once per show; this row is what gets locked.
  CONSTRAINT uq_show_seat      UNIQUE (show_id, seat_id),
  -- The claim query looks seats up by code; this index also serves seat-map reads by show_id.
  CONSTRAINT uq_show_seat_code UNIQUE (show_id, seat_code)
);
CREATE INDEX idx_show_seats_hold ON inventory.show_seats(hold_id) WHERE hold_id IS NOT NULL;

-- ---------------------------------------------------------------- demo data
-- Fixed ids so tests, the README and k6 scripts can refer to them.
INSERT INTO inventory.venues (id, name, city)
VALUES ('00000000-0000-0000-0000-000000000010', 'Demo Arena', 'Bengaluru');

INSERT INTO inventory.venue_sections (id, venue_id, code, default_price_minor)
VALUES ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000010', 'A', 150000);

-- 100 seats A1..A100, laid out as 10 rows of 10.
INSERT INTO inventory.seats (id, section_id, seat_code, row_no, col_no)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000011', 'A' || n, (n - 1) / 10 + 1, (n - 1) % 10 + 1
FROM generate_series(1, 100) AS n;

INSERT INTO inventory.events (id, event_code, name, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'demo-concert', 'Demo Concert', 'PUBLISHED');

INSERT INTO inventory.shows (id, event_id, venue_id, starts_at, sale_opens_at, status)
VALUES ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000010', '2027-01-15T18:00:00Z', '2026-11-01T00:00:00Z', 'ON_SALE');

-- What admin show creation (P6.9) will do: one show seat per venue seat, priced from its section.
INSERT INTO inventory.show_seats (id, show_id, seat_id, seat_code, section_code, status, price_minor)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000101', s.id, s.seat_code, vs.code, 'AVAILABLE', vs.default_price_minor
FROM inventory.seats s
JOIN inventory.venue_sections vs ON vs.id = s.section_id
WHERE vs.venue_id = '00000000-0000-0000-0000-000000000010';

-- ---------------------------------------------------------------- retire V1
DROP TABLE public.seat_inventory;
DROP TABLE public.holds;
DROP TABLE public.events;
