# Seat-locking strategies: benchmark (P1.8)

Five implementations of `SeatLockingStrategy` (P1.7), measured under the same four workloads and one failure case. The decision taken from these numbers is [ADR 003](../adr/003-seat-locking-strategy.md).

## Setup

| Item | Value |
|---|---|
| Harness | `ticketing-app/src/test/java/.../inventory/infrastructure/locking/LockingBenchmarkIT.java` (not run in CI) |
| How to rerun | `mvn -pl ticketing-app -am verify -Dbenchmark=true -Dit.test=LockingBenchmarkIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` |
| Machine | Apple M2, 8 cores, 8 GB; Docker Desktop VM with 8 CPUs / 4 GB; Java 27 (bytecode 21) |
| Database | PostgreSQL 16 (Testcontainers), HikariCP pool of 50 connections |
| Redis | Redis 7 (Testcontainers), command timeout 500 ms |
| Show | 5,000 seats (`B1…B5000`), reset and `VACUUM ANALYZE` before every run |
| Method | Every attempt is a real `CreateHoldUseCase` transaction on a virtual thread; all attempts released by one start gate. 1 warm-up + 3 measured runs; throughput = median run; percentiles over all 3 runs (accepted + rejected requests). |
| Correctness gate | After **every** run: `InventoryInvariants.assertHold` (every live hold owns all its seats, no orphan seats). All runs passed. |

**Workloads**
- **hot-seat:** 1,000 attempts on 1 seat (one winner, everyone else must lose fast).
- **flash-sale:** 5,000 single-seat attempts on 500 seats (10 per seat).
- **multi-seat:** 2,000 requests of 2–4 seats over 50 seats, each in random order (deadlock bait).
- **uncontended:** 2,000 attempts on 2,000 different seats (the cost of the mechanism itself).

## Results

| Strategy | Scenario | Throughput (attempts/s) | p50 ms | p95 ms | p99 ms | Winners | Rejected | Deadlocks | Other errors |
|---|---|---|---|---|---|---|---|---|---|
| CONDITIONAL_UPDATE | hot-seat | 3879 | 158.6 | 335.6 | 368.0 | 1 | 999 | 0 | 0 |
| PESSIMISTIC | hot-seat | 2289 | 234.4 | 462.5 | 506.6 | 1 | 999 | 0 | 0 |
| OPTIMISTIC | hot-seat | 5429 | 107.8 | 180.6 | 200.5 | 1 | 999 | 0 | 0 |
| REDIS_LOCK | hot-seat | 5308 | 102.9 | 182.4 | 188.3 | 1 | 999 | 0 | 0 |
| REDIS_LUA | hot-seat | 5803 | 78.4 | 321.7 | 336.8 | 1 | 999 | 0 | 0 |
| CONDITIONAL_UPDATE | flash-sale | 5842 | 453.2 | 835.9 | 910.4 | 500 | 4500 | 0 | 0 |
| PESSIMISTIC | flash-sale | 5965 | 441.7 | 795.2 | 828.4 | 500 | 4500 | 0 | 0 |
| OPTIMISTIC | flash-sale | 5639 | 529.2 | 1080.8 | 1182.1 | 500 | 4500 | 0 | 0 |
| REDIS_LOCK | flash-sale | 4389 | 612.2 | 1081.1 | 1140.2 | 500 | 4500 | 0 | 0 |
| REDIS_LUA | flash-sale | 7948 | 339.2 | 595.3 | 622.8 | 500 | 4500 | 0 | 0 |
| CONDITIONAL_UPDATE | multi-seat | 5548 | 188.9 | 346.8 | 359.7 | 19 | 1981 | 0 | 0 |
| PESSIMISTIC | multi-seat | 4327 | 229.3 | 431.9 | 455.2 | 19 | 1980 | 0 | 0 |
| OPTIMISTIC | multi-seat | 5736 | 183.4 | 328.3 | 345.8 | 21 | 1978 | 0 | 0 |
| REDIS_LOCK | multi-seat | 6621 | 162.3 | 289.8 | 308.5 | 20 | 1980 | 0 | 0 |
| REDIS_LUA | multi-seat | 8270 | 116.4 | 232.3 | 253.3 | 19 | 1980 | 0 | 0 |
| CONDITIONAL_UPDATE | uncontended | 5404 | 191.9 | 347.2 | 367.3 | 2000 | 0 | 0 | 0 |
| PESSIMISTIC | uncontended | 3903 | 278.4 | 491.1 | 535.4 | 2000 | 0 | 0 | 0 |
| OPTIMISTIC | uncontended | 3592 | 278.6 | 539.3 | 583.8 | 2000 | 0 | 0 | 0 |
| REDIS_LOCK | uncontended | 3250 | 337.7 | 766.2 | 843.4 | 2000 | 0 | 0 | 0 |
| REDIS_LUA | uncontended | 4613 | 217.6 | 410.7 | 429.4 | 2000 | 0 | 0 | 0 |
**Read this first:**
- **The latencies are dominated by queueing:** thousands of attempts start at the same instant and share 50 connections. p50 of 150–600 ms is mostly waiting for a connection, not lock time. Compare strategies *with each other* in this table, not with NFR-03 (p95 < 250 ms at *admitted* load, behind the waiting room, which P9 measures).
- **Laptop, one Docker VM, about 10% run-to-run noise:** small differences (for example 5,842 vs 5,965/s) are a tie.

## Redis unavailable (Redis container paused for the whole run, flash-sale)

| Strategy | Winners | Failed | Deadlocks | p99 ms | Invariants |
|---|---|---|---|---|---|
| CONDITIONAL_UPDATE | 500 | 4500 | 0 | 1463 | hold ✓ |
| PESSIMISTIC | 500 | 4500 | 0 | 1366 | hold ✓ |
| OPTIMISTIC | 500 | 4500 | 0 | 923 | hold ✓ |
| REDIS_LOCK | 0 | 5000 | 0 | 30097 | hold ✓ |
| REDIS_LUA | 0 | 5000 | 0 | 30041 | hold ✓ |

- **Postgres-only strategies:** they don't notice the outage.
- **Redis strategies:** both stop selling entirely. They *fail closed*, so the invariants still hold, but p99 is about **30 s**, not the 500 ms Redis timeout. The reason is that the Redis call happens **inside** the database transaction, which already holds a pooled connection. While Redis hangs, the 50 connections are all stuck, and other requests wait out Hikari's 30 s `connectionTimeout`. A slow dependency inside a transaction turns into database-pool exhaustion.

## Analysis

| Strategy | Strengths seen | Weaknesses seen |
|---|---|---|
| **Conditional update** | Fastest when uncontended (one statement, one round trip); competitive everywhere else; no deadlocks; no second system; unaffected by Redis | Losers on a hot row wait for the winner's row lock before being rejected (hot-seat p95 336 ms vs 181 ms optimistic) |
| **Pessimistic** | Simple to reason about; ties on flash-sale | Slowest on hot-seat and multi-seat: locks are held through the Java round trip and also on seats that turn out to be taken |
| **Optimistic** | Very good hot-seat: losers read the committed `HELD` without taking any lock | Worst p99 on flash-sale (version races, retries); about 33% slower than conditional update when uncontended (read + write per seat) |
| **Redis lock** | Fast rejection of losers (`SETNX` fails in Redis) | Slowest when uncontended (2 + N Redis calls per request); total outage when Redis is down; correctness still needs fencing plus a database check (ADR 003) |
| **Redis Lua (filter + conditional update)** | Best under contention (flash-sale +36%, multi-seat +49%): most losers never touch Postgres | Extra Redis round trip costs about 15% when uncontended; total outage when Redis is down **in this placement**; marks drift from the database until synced (P2.3–P2.5) |

**Conclusions:**
1. **Correctness:** every strategy passed the same contract suite (P1.7) and every benchmark invariant check. The choice is about cost and failure behaviour, not correctness.
2. **Postgres-side strategy:** the conditional update is the best authority. It's never far from the best, is fastest when there's no fight, and has no external dependency.
3. **Redis only as a filter:** the fast-reject idea clearly pays off under flash-sale contention, but **only as a filter outside the database transaction, with a fallback**. Called inside the transaction, it turns a Redis outage into a full outage.
4. **Redis locks give nothing here:** they add latency and a failure mode, and they still depend on the database for safety.

**Not measured yet:**
- **`SERIALIZABLE` and `SKIP LOCKED` variants:** noted in P1.7.
- **Throughput behind the waiting room:** at admitted load, measured in P9.
- **A partitioned `show_seats`:** P7.3.
