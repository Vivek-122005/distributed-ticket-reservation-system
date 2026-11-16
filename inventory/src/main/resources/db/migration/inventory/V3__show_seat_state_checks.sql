-- P1.3: the seat state machine (plan §4.3) enforced by the database as well as by the domain,
-- so no code path (a future strategy, a manual fix, a bug) can write an impossible row.

-- No "available but held" ghost state: an AVAILABLE seat carries no hold.
ALTER TABLE inventory.show_seats
  ADD CONSTRAINT ck_available_has_no_hold
  CHECK (status <> 'AVAILABLE' OR (hold_id IS NULL AND held_by_user_id IS NULL AND hold_expires_at IS NULL));

-- Invariant 4: every HELD seat belongs to a hold and can expire.
ALTER TABLE inventory.show_seats
  ADD CONSTRAINT ck_held_has_hold_and_expiry
  CHECK (status <> 'HELD' OR (hold_id IS NOT NULL AND held_by_user_id IS NOT NULL AND hold_expires_at IS NOT NULL));

-- A BOOKED seat keeps the hold it was booked through (audit trail to the booking).
ALTER TABLE inventory.show_seats
  ADD CONSTRAINT ck_booked_has_hold
  CHECK (status <> 'BOOKED' OR hold_id IS NOT NULL);
