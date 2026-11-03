# ADR 001: PostgreSQL is the inventory authority

## Decision

PostgreSQL is authoritative. A hold succeeds only when every requested seat is conditionally updated from `AVAILABLE` to `HELD` inside one transaction.

## Consequences

- Redis can fail without allowing double booking.
- A multi-seat request holds every requested seat or none.
- Redis is reserved for rate limiting, waiting-room admission, and caching.
- Critical SQL is indexed, short, and must be load-tested.
