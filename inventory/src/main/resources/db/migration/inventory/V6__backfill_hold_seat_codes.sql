-- P2.1: repair holds created before V4 (dev databases only). V4 added seat_codes with an empty default
-- and a NOT VALID check, so those rows could never be updated again: the first expiry batch that
-- touched one failed, and because the worker takes the oldest holds first, it failed forever.

-- 1. Recover the seat codes from the seats that still point at the hold.
UPDATE inventory.holds h
   SET seat_codes = owned.codes
  FROM (SELECT hold_id, array_agg(seat_code ORDER BY seat_code)::varchar(20)[] AS codes
          FROM inventory.show_seats WHERE hold_id IS NOT NULL GROUP BY hold_id) owned
 WHERE h.id = owned.hold_id AND cardinality(h.seat_codes) = 0;

-- 2. Legacy holds that own no seat any more carry no information worth keeping.
DELETE FROM inventory.holds h
 WHERE cardinality(h.seat_codes) = 0
   AND NOT EXISTS (SELECT 1 FROM inventory.show_seats s WHERE s.hold_id = h.id);

-- 3. Every row now satisfies the rule, so it can be fully validated.
ALTER TABLE inventory.holds VALIDATE CONSTRAINT ck_hold_seat_count;
