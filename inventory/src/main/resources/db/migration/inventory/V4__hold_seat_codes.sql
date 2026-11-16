-- P1.4: a hold remembers which seats it was for, so get / confirm / release can answer with the
-- seats (and their frozen prices, read from show_seats by code) even after the seats were released
-- or lazily re-held by someone else.
ALTER TABLE inventory.holds ADD COLUMN seat_codes VARCHAR(20)[] NOT NULL DEFAULT '{}';
ALTER TABLE inventory.holds ALTER COLUMN seat_codes DROP DEFAULT;

-- BR-01 / FR-03: 1-8 seats per hold. NOT VALID: enforced for every new or updated row, but holds
-- created before V4 (dev databases only) keep their empty array instead of failing the migration.
ALTER TABLE inventory.holds
  ADD CONSTRAINT ck_hold_seat_count CHECK (cardinality(seat_codes) BETWEEN 1 AND 8) NOT VALID;
