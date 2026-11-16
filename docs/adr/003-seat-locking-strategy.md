# ADR 003: Conditional update as the seat-locking authority; Redis only as an outside filter

- **Status:** Accepted (2026-11-16)
- **Deciders:** Vivek Vishnoi, Yeeshu
- **Plan parts:** P1.7 (strategies), P1.8 (benchmark, this ADR); affects P2.4 and P2.6
- **Evidence:** [docs/results/locking-comparison.md](../results/locking-comparison.md)

## Context

Holding seats is the hottest and most correctness-critical path: thousands of requests for the same few seats, exactly one winner per seat, all-or-nothing for multi-seat holds, no deadlocks (FR-03, NFR-01). Five approaches were built behind one `SeatLockingStrategy` port and must all pass the same contract suite:

- (a) pessimistic `SELECT … FOR UPDATE`;
- (b) optimistic `version` check with retry;
- (c) conditional update: one ordered `UPDATE … WHERE claimable RETURNING`;
- (d) Redis `SET NX PX` locks with a fencing token;
- (e) a Redis Lua check-and-set filter in front of (c).

ADR 001 already makes PostgreSQL the authority for seat ownership. The open question was which mechanism to use, and where Redis helps.

## Decision

1. **Production strategy: (c) conditional update** (`inventory.locking.strategy=conditional-update`, the default).
2. **Redis Lua (e) will be used only as a fast-reject filter, and moved outside the database transaction** (P2.4).
   - The Lua check-and-set runs *before* a connection is taken.
   - On a Redis error or timeout the request skips the filter and goes straight to (c) (fail open to the authority, P2.6), with a short timeout and a circuit breaker.
   - Marks are kept in sync from inventory events (P2.3), and drift is repaired by reconciliation (P2.5).
3. **(a), (b) and (d) stay in the codebase** as documented, tested alternatives for the comparison. They are selectable by configuration but not recommended:
   - **(d) Redis lock** is rejected for production.
   - **(a) pessimistic** and **(b) optimistic** offer nothing over (c) for this workload.

## Rationale (from the measurements)

- **(c) is never far from the best and is the best when uncontended:**
  - **Uncontended:** 5,404 attempts/s vs 3,250–4,613 for the others.
  - **Flash-sale:** tied with (a), better than (b) and (d).
  - **Its one weakness:** losers on a single hot row wait for the winner's row lock (hot-seat p95 336 ms vs 181 ms for (b)). That is the case the filter and the waiting room (P6) address.
- **(e) wins under contention:** +36% on flash-sale and +49% on multi-seat, because most losers never reach Postgres. That is worth having on hot sales.
- **Redis inside the transaction is dangerous:** with Redis paused, (d) and (e) sold **nothing**, and p99 rose to about 30 s. Their Redis calls run while a database connection is held, so a hung Redis exhausts the pool and blocks everyone. This is why (e) must move outside the transaction and fail open to (c).
- **(d) adds cost without adding safety:** 2 + N Redis round trips per request (slowest uncontended), a total outage when Redis is down, and correctness that still depends on a fencing token checked by Postgres. Even with fencing, an expired lock can let a stale holder win before the new holder writes (Kleppmann). Since Postgres must check anyway, the lock only duplicates the conditional update.
- **(a) holds locks longer than needed:** locks are held across a Java round trip, and also on seats that turn out to be taken. It was the slowest on hot-seat and multi-seat.
- **(b) wastes work under contention:** retries under contention gave the worst flash-sale p99 (1,182 ms), and the read-then-write costs about 33% more when uncontended.

## Consequences

- **Positive:**
  - One SQL statement holds the whole correctness argument (ordered locking, compare-and-set, lazy expiry). It is easy to review and is mutation-tested (P1.6).
  - The hold path has no runtime dependency on Redis; a Redis outage can only make it slower, never unavailable (after P2.4/P2.6).
  - The strategy port remains, so this decision can be revisited with P9 load numbers by changing one property.
- **Negative:**
  - Single-row hot spots still serialise on the row lock. This is mitigated by the Redis filter (P2.4) and admission control (P6), not by the lock mechanism.
  - Keeping the Redis filter in sync is extra work (P2.3–P2.5). Until then, the Lua strategy is benchmark-only.
- **Follow-ups:**
  - **P2.4:** move the filter out of the transaction.
  - **P2.6:** feature-flagged bypass on Redis failure.
  - **P9:** repeat the comparison at admitted load behind the waiting room.
  - **P7.3:** measure partitioning.

## Alternatives considered

- **Make Redis the authority (Lua only, no database claim):** fastest of all, but Redis persistence and failover can lose acknowledged writes. Rejected by ADR 001.
- **`SERIALIZABLE` isolation:** correct, but conflict handling turns into client retries. It's similar to (b) and was not benchmarked.
- **`FOR UPDATE NOWAIT` / `SKIP LOCKED`:** faster rejection, but they wrongly refuse a seat whose locker later rolls back. Kept as a possible variant if P9 shows the lock waits matter.
