-- P1.7: fencing token for the Redis-lock strategy (DESIGN_PATTERNS #20). A write is accepted only
-- with a token larger than the last one written, so a client whose Redis lock silently expired
-- (GC pause, network delay) cannot overwrite a newer lock holder's write. Unused by the other strategies.
ALTER TABLE inventory.show_seats ADD COLUMN lock_fence BIGINT NOT NULL DEFAULT 0;
