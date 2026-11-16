# 5. Consistency & failure model

## 5.1 Where consistency is strong vs eventual

| Data / decision | Consistency | Mechanism | Why this level |
|---|---|---|---|
| Who holds / owns a seat | **Strong (linearizable per seat)** | Single Postgres primary, conditional `UPDATE` (compare-and-set) | Oversell is unacceptable |
| One booking per hold | **Strong** | `UNIQUE (hold_id)` | Duplicate saga runs must not create two bookings |
| One payment per booking | **Strong** | `UNIQUE (booking_id)` in payments + provider idempotency key | No double charge |
| Booking ↔ seat ↔ payment across services | **Eventual, convergent** | Saga with persisted state, retries, compensations, recovery job | No distributed transaction exists across services + PSP |
| Seat-map availability shown to users | **Eventual (sub-second)** | Redis cache updated from inventory events | A slightly stale map only causes a fast rejection, never an oversell |
| Notifications, tickets, analytics | **Eventual** | Outbox → Kafka → idempotent consumers | Side effects can lag; they must not be lost or duplicated |
| Waiting-room position | **Approximate** | Redis `ZRANK`, ETA from admission rate | Informational only |

**CAP framing:** for seat ownership the system chooses **consistency over availability** — if the Postgres primary is unreachable, holds fail (`503`) rather than risk two owners. Everything else is designed to stay available and converge later.

## 5.2 Delivery semantics

| Channel | Semantics | How duplicates are made harmless |
|---|---|---|
| Client → Booking | At-least-once (clients retry) | `Idempotency-Key` table |
| Booking → Inventory (hold/confirm/release) | At-least-once (retries) | Confirm/release are conditional on `hold_id` and idempotent; hold is protected by the booking's idempotency key |
| Booking → Payment | At-least-once | `bookingId` as payment idempotency key + unique constraint |
| PSP → Booking (webhook) | At-least-once, possibly out of order | Idempotent receiver + state machine ignores stale transitions |
| Outbox → Kafka | At-least-once | Consumers dedupe on `eventId` |
| Kafka → consumers | At-least-once | `processed_events` insert in the same transaction as the side effect |

**Rule of the system:** *every message may be duplicated; no message may be lost.* Loss is prevented by persisting before acknowledging (outbox, saga state); duplication is neutralised by idempotency everywhere. Together this gives **effectively-once processing**.

**Why not Kafka exactly-once transactions?** They only cover Kafka → Kafka processing. Our side effects write to Postgres, call a PSP and send emails — outside Kafka's transaction — so idempotent consumers are needed anyway.

## 5.3 Failure matrix

| Failure | Impact | Detection | Recovery | Correctness preserved because… | Demo |
|---|---|---|---|---|---|
| A booking-service replica dies | In-flight requests on that replica fail; clients retry | LB health check / k8s readiness | Other replicas serve; saga recovery job resumes stuck sagas | Saga state persisted per step; idempotent retries | P10.1 |
| An inventory replica dies mid-hold | That transaction rolls back | Health check | Retry hits another replica | Postgres transaction is atomic; no partial holds | P10.2 |
| Postgres primary down | Holds/bookings fail with `503`; waiting room keeps queueing | Connection errors, alerts | Restart/failover; system resumes | Fail closed — no writes means no oversell | — (documented) |
| Redis down | Fast-reject and seat-map cache off; rate limiter & waiting room degraded | Redis client errors, metric | Inventory bypasses Redis (feature flag); waiting room fails closed for new admissions | Postgres stays the authority (ADR 001) | P10.3 |
| Kafka down | Notifications/tickets delayed | Relay publish errors, outbox lag metric | Outbox accumulates; drains when Kafka returns | Outbox row committed with state change → nothing lost | P10.4 |
| Duplicate Kafka message | Consumer sees event twice | — | Second one skipped | `processed_events` dedup | P10.5 |
| Webhook duplicated / out of order | Same payment result twice, or `Failed` after `Captured` | — | Ignored by state machine | Transitions validated against current state | P10.6 |
| Payment captured after hold expiry | Customer paid, seat may be resold | Booking in `EXPIRED` receives capture | Automatic refund | Late-payment path (flow 3.6) | P10.6 |
| Gateway timeout during capture *(rev)* | Outcome unknown: maybe charged, maybe not | Gateway call times out | Payment → `UNKNOWN`; reconciliation asks the gateway by idempotency key | Seats are neither released nor booked on a guess; a late capture goes to refund | P10.7 |
| Crash between capture and confirm | Paid but seats still `HELD` | Saga stuck past `next_retry_at` | Recovery job / webhook retry resumes | Idempotent confirm; expiry → refund path | P10.8 |
| Slow inventory (latency spike) | Booking threads pile up | p99 metric, circuit breaker | Breaker opens → fast `503`; waiting room lowers admission rate | Fails fast instead of cascading | P10.7 |
| Expiry worker stopped | Expired holds not cleaned up | Hold-age metric | Restart | Lazy expiry in claim `WHERE` makes expired seats claimable anyway | P1.2 |
| Poison message in a topic | One message keeps failing | Retry-topic counts, DLQ alert | Moves to DLQ; partition keeps flowing | Non-blocking retries | P5.9 |
| Clock skew between replicas | Expiry decisions differ slightly | — | — | Expiry compared with **database** `now()`, not app clocks | P1.2 |

## 5.4 Invariants and how each is checked

| # | Invariant | Enforced by | Verified by |
|---|---|---|---|
| 1 | ≤ 1 active hold/booking per show seat | Row-level compare-and-set; `UNIQUE (show_id, seat_id)` | Concurrency tests (P1.6); post-load SQL (P9.2) |
| 2 | Booked seats = seats of confirmed bookings | Confirm only via saga | Post-load reconciliation SQL across schemas (P9.2) |
| 3 | Captured payment ⇒ confirmed booking or refund | Saga + late-payment path | Post-load SQL; chaos tests (P10.6, P10.8) |
| 4 | Every `HELD` seat has an expiry | `CHECK` constraint | Constraint itself; tests |
| 5 | No payment `UNKNOWN` past the reconciliation window *(rev)* | Reconciliation job (P4.8) | `payments_unknown` metric + alert; chaos test P10.7 |

The post-load checker (P9.2) fails the run if any invariant is violated, so "0 oversold" in the demo is a computed result, not a claim.

## 5.5 Timeouts and retry policy (defaults, tuned in P9)

| Call | Timeout | Retries | Backoff | Breaker |
|---|---|---|---|---|
| Booking → Inventory hold | 500 ms | 0 (not retried automatically; idempotency key lets the client retry safely) | — | 50 % failures over 20 calls → open 5 s |
| Booking → Inventory confirm/release | 1 s | 5 | Exponential 100 ms → 5 s + jitter | Yes |
| Booking → Payment create intent | 2 s | 2 | Exponential + jitter | Yes |
| Payment → PSP capture *(rev)* | 5 s | 0 (a timeout becomes `UNKNOWN`) | — | Yes |
| Reconciliation lookup *(rev)* | 5 s | every minute until resolved | Fixed 1 min | — |
| Saga recovery | — | until hold expiry, then compensation | Exponential up to 1 min | — |
| Kafka consumer | — | in-place 3, then retry topics 1 m, 5 m, then DLQ | Fixed per stage | — |

**Why jitter:** after an outage, thousands of retries without jitter would arrive in synchronized waves (thundering herd) and knock the service over again.
