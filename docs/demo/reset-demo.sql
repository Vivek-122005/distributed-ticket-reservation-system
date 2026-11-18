-- Resets the LOCAL demo database to 100 free seats and no bookings, between demo runs.
-- Destructive for demo data only. Run with:
--   docker compose exec -T postgres psql -U ticket -d ticket_reservation < docs/demo/reset-demo.sql
BEGIN;
DELETE FROM payment.webhook_events;
DELETE FROM payment.idempotency_keys;
DELETE FROM payment.payments;
DELETE FROM booking.booking_sagas;
DELETE FROM booking.idempotency_keys;
DELETE FROM booking.booking_seats;
DELETE FROM booking.bookings;
UPDATE inventory.show_seats
   SET status = 'AVAILABLE', hold_id = NULL, held_by_user_id = NULL, hold_expires_at = NULL, version = version + 1;
DELETE FROM inventory.holds;
UPDATE inventory.shows SET status = 'ON_SALE';
COMMIT;
-- Redis seat cache: the version bump above makes the reconciler overwrite every cached seat within
-- 30 s; to refresh at once: docker compose exec redis redis-cli --scan --pattern 'seatstate:*' | xargs docker compose exec -T redis redis-cli del
