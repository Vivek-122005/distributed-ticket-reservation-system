# 2. Capacity estimation

Back-of-envelope numbers that justify the design. They are **assumptions to be validated** by the load tests in Phase 9; the results will be recorded next to these estimates in `docs/results/`.

## 2.1 Reference scenario: one hot event

| Parameter | Value | Note |
|---|---|---|
| Seats | 50,000 | Large stadium concert |
| Sections | 25 × 2,000 seats | Used for partitioning (P7) |
| Users arriving at sale open | 100,000 in 30 s | 2 users per seat |
| Seats per booking (average) | 2 | ~25,000 bookings to sell out |
| Hold TTL | 5 minutes | |
| Admitted concurrent sessions | 2,000 | Waiting-room cap |
| Queue poll interval | 5 s | Client long-poll / refresh |

## 2.2 Traffic

| Flow | Calculation | Rate |
|---|---|---|
| Arrivals at edge | 100,000 / 30 s | **~3,300 req/s** (enqueue) |
| Queue status polling | 100,000 waiting / 5 s | **~20,000 req/s** — Redis `ZRANK`, O(log n) |
| Admissions | 2,000 slots, each session ~60 s | ~33 admissions/s steady |
| Seat-map reads | 2,000 sessions × 1 read / 2 s | ~1,000 req/s — served from Redis cache (P2.3) |
| Hold attempts | 2,000 sessions × ~3 attempts in first 10 s | **~600/s steady, bursts to ~3,000/s** |
| Payment + confirm | 25,000 bookings over ~15 min | ~30/s |
| Kafka events | ~5 events per booking × 25,000 | ~125,000 events total; ~150/s peak |

**Reading:** the edge (20k req/s) is the highest raw volume, but it is cheap Redis work. The **hold path (~3,000/s burst)** is the critical one, because every request competes for the same rows.

## 2.3 Why admission control is required

Without a waiting room, 100,000 users × ~3 attempts ≈ 300,000 hold attempts in the first 30 s ≈ **10,000/s against Postgres**, almost all for the same popular seats. Each failed attempt still costs a transaction, a connection and a round trip. Capping active sessions at 2,000 bounds the hold rate to a level a single Postgres primary can serve with low latency, and the remaining users get a fair position instead of timeouts.

## 2.4 Storage

| Data | Size per row | Rows | Total |
|---|---|---|---|
| `seat_inventory` | ~200 B | 50,000 | ~10 MB |
| `holds` | ~150 B | ~100,000 (incl. expired) | ~15 MB |
| `bookings` | ~250 B | ~25,000 | ~6 MB |
| `idempotency_keys` (24 h TTL) | ~1 KB (stored response) | ~200,000 | ~200 MB |
| `outbox` (pruned after publish) | ~1 KB | ~125,000 | ~125 MB before pruning |
| Kafka (7-day retention) | ~1 KB | ~125,000 / event | ~125 MB per hot event |

**Reading:** everything fits in memory on one machine. Storage is not a design driver; **contention and burst handling are.**

## 2.5 Redis memory

| Key | Size | Total |
|---|---|---|
| Waiting room ZSET (100,000 members × ~60 B) | | ~6 MB |
| Seat availability (bitmap/hash per section) | 50,000 seats | < 1 MB |
| Rate-limit buckets (100,000 users × ~100 B) | | ~10 MB |
| Admission sessions | 2,000 | negligible |

Redis runs with `maxmemory-policy noeviction` (already set in Compose): evicting a queue entry or rate-limit bucket silently would be a correctness bug, so it's better to fail loudly.

## 2.6 Database connections

- Postgres handles a few hundred connections comfortably; throughput peaks at roughly `2–4 × CPU cores` **active** connections.
- Hold transactions are short (~2–5 ms): one batched conditional `UPDATE` plus one `INSERT`.
- At 3,000 holds/s × 5 ms ≈ **15 connections busy on average** → a pool of ~20 per inventory replica × 3 replicas = 60 is ample.
- Little's law check: `L = λ × W = 3,000/s × 0.005 s = 15` concurrent transactions.
- **Implication:** a large connection pool would *hurt* (more lock contention, more context switching). Pool sizing is tuned in P7.4.

## 2.7 Kafka partitions

| Topic | Peak rate | Partitions | Reason |
|---|---|---|---|
| `booking.events` | ~150/s | 6 | Allows up to 6 parallel consumers per group; plenty of headroom |
| `inventory.events` | ~3,000/s at burst (hold/release) | 12 | Keyed by `eventId:section` → 25 sections spread across 12 partitions |
| `payment.events` | ~30/s | 6 | Same key as bookings (`bookingId`) |

Partition count is chosen for **consumer parallelism and key spread**, not throughput; a single partition handles far more than these rates.

## 2.8 Latency budget (hold request, p99 target 500 ms)

| Hop | Budget |
|---|---|
| Gateway (auth + rate limit Lua) | 10 ms |
| Booking service (idempotency insert) | 20 ms |
| Inventory: Redis fast-reject (P2.4) | 2 ms |
| Inventory: Postgres conditional update + commit | 20 ms (p99 under contention) |
| Network hops (4 × ~2 ms) | 10 ms |
| **Total expected p99** | **~60–100 ms**, leaving headroom for GC pauses and queueing |

The 500 ms target is intentionally loose; the expectation is p99 well below it at admitted traffic levels.
