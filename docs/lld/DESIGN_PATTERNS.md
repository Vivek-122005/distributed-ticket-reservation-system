# Design patterns registry

The record of **every design pattern used in this codebase**: where it lives (class and file), what problem it solves there, why it was chosen, and what the alternative was.

**Rules** (plan §12, rule 6):
- A change that introduces or removes a pattern updates this file **in the same commit**.
- The class that applies the pattern carries a one-line Javadoc pointer, for example `/** Pattern: Strategy, see docs/lld/DESIGN_PATTERNS.md#strategy */`.
- An entry stays ⬜ *planned* until the code exists. It then becomes ✅ with real file paths. Nothing is listed as implemented before it is.

Class-level view of where these patterns sit: [class-diagrams.md](class-diagrams.md).

Legend: ✅ implemented · 🔨 partly implemented · ⬜ planned (plan part in brackets)

## Summary

| # | Pattern | Category | Status | Where (main class) |
|---|---|---|---|---|
| 1 | [Ports & Adapters (Hexagonal)](#1-ports--adapters-hexagonal) | Architectural | ✅ inventory (P1.3) | `application.port.*` ← `infrastructure.*` |
| 2 | [Repository](#2-repository) | Persistence | ✅ | `HoldStore` ← `JdbcHoldStore`, `ShowStore` ← `JdbcShowStore` |
| 3 | [Compare-and-Set (conditional update)](#3-compare-and-set-conditional-update) | Concurrency | ✅ | `ConditionalUpdateStrategy.claim` |
| 4 | [Data Transfer Object](#4-data-transfer-object) | API | ✅ | `CreateHoldRequest`, `HoldResponse`, `ApiError` |
| 5 | [Centralised exception translation](#5-centralised-exception-translation) | API | ✅ | `ApiExceptionHandler` |
| 6 | [State](#6-state) | Behavioural (GoF) | 🔨 ✅ inventory (P1.3); ⬜ P3.2, P4.2 | `SeatStatus`/`HoldStatus`/`ShowStatus` + `ShowSeat`, `Hold`, `Show`; later `Booking`, `Payment` |
| 7 | [Strategy](#7-strategy) | Behavioural (GoF) | ✅ P1.7 | `SeatLockingStrategy` ← 5 strategies, chosen by `SeatLockingConfig` |
| 8 | [Adapter](#8-adapter) | Structural (GoF) | ⬜ P4.1, P3.3 | `PaymentGateway`, `InventoryApi` adapters |
| 9 | [Facade](#9-facade) | Structural (GoF) | 🔨 P0.14 (inventory) | `InventoryApi` → `InventoryFacade`; `PaymentApi` in P4.1 |
| 10 | [Saga (Orchestrator)](#10-saga-orchestrator) | Distributed | ⬜ P4.4 | `BookingSaga` |
| 11 | [Transactional Outbox](#11-transactional-outbox) | Distributed | ⬜ P5.2–P5.3 | `OutboxWriter`, `OutboxRelay` |
| 12 | [Idempotent Receiver / Idempotency Key](#12-idempotent-receiver--idempotency-key) | Distributed | ⬜ P3.4, P5.5 | `IdempotencyInterceptor`, `IdempotentEventConsumer` |
| 13 | [Template Method](#13-template-method) | Behavioural (GoF) | ⬜ P5.5 | `IdempotentEventConsumer` |
| 14 | [Observer / Publish-Subscribe](#14-observer--publish-subscribe) | Behavioural (GoF) | 🔨 in-process ✅ P2.1; Kafka ⬜ P5 | `InventoryEventPublisher` → `@TransactionalEventListener`s; later outbox → Kafka |
| 15 | [Factory](#15-factory) | Creational (GoF) | ⬜ P5.1 | `EventEnvelopeFactory` |
| 16 | [Builder](#16-builder) | Creational (GoF) | ⬜ P3.6 (not needed in P1.6) | test-data builders |
| 17 | [Chain of Responsibility](#17-chain-of-responsibility) | Behavioural (GoF) | ⬜ P6.1–P6.5 | gateway filters |
| 18 | [Circuit Breaker / Retry / Bulkhead](#18-circuit-breaker--retry--bulkhead) | Resilience | ⬜ P7.8 | HTTP adapters (Resilience4j) |
| 19 | [Token Bucket](#19-token-bucket) | Rate limiting | ⬜ P6.3 | `token_bucket.lua` |
| 20 | [Fencing token](#20-fencing-token) | Concurrency | ✅ P1.7 | `RedisLockStrategy` + `show_seats.lock_fence` |
| 22 | [Decorator](#22-decorator) | Structural (GoF) | ✅ P1.7, P2.3 | `RedisLuaStrategy` wraps `ConditionalUpdateStrategy`; `CachedCatalogReadStore` wraps `JdbcCatalogReadStore` |
| 23 | [Competing consumers (SKIP LOCKED work queue)](#23-competing-consumers-skip-locked-work-queue) | Concurrency | ✅ P2.1–P2.2 | `ExpireHoldsJob` via `JdbcHoldStore.lockExpired` |
| 24 | [Versioned last-writer-wins](#24-versioned-last-writer-wins-order-independent-cache-updates) | Consistency | ✅ P2.3 | `SeatStateCache.APPLY` + row `version` in `SeatsChanged` |
| 25 | [Null Object](#25-null-object) | Behavioural | ✅ P2.4 | `NoSeatFilter` (pass-through `SeatFilter`) |
| 26 | [Fast-reject filter (cache as filter, DB as authority)](#26-fast-reject-filter-cache-as-filter-db-as-authority) | Performance | ✅ P2.4 | `FilteredCreateHold` + `RedisSeatFilter` |
| 27 | [Reconciliation (anti-entropy)](#27-reconciliation-anti-entropy) | Consistency | ✅ P2.5 | `SeatCacheReconciler` |
| 21 | [Read/write separation (CQRS-lite)](#21-readwrite-separation-cqrs-lite) | Architectural | ✅ P1.5, Redis read model P2.3 | `CatalogQuery`, `SeatMapQuery` → `CatalogQueries` → `CatalogReadStore` |

---

## 1. Ports & Adapters (Hexagonal)
- **Status:** ✅ in the inventory module (inbound P0.14, outbound P1.3). Booking and payment follow the same layout in P3/P4.
- **Where:** `inventory/src/main/java/com/vivek/ticketreservation/inventory/`:
  - **inbound:** `web.InventoryController` (HTTP adapter) → `api.InventoryApi` (published port) → `application.*UseCase`.
  - **outbound:** `application.port.{SeatLockingStrategy, HoldStore, ShowStore}` (ports owned by the application layer) ← `infrastructure.{ConditionalUpdateStrategy, JdbcHoldStore, JdbcShowStore}` (package-private JDBC adapters).
  - **enforced by** `ticketing-app/.../architecture/ModuleBoundaryTest.java`: `applicationDependsOnPortsNotAdapters`, `applicationDoesNotDependOnWeb`, `domainIsFrameworkFree`, `domainDoesNotDependOnOuterLayers`.
- **Problem:** business rules (who may hold a seat) must not depend on HTTP, SQL or Redis, because the locking implementation has to be swappable for the benchmark (P1.7) and modules must be extractable later (ADR 002).
- **Why:** the domain and use cases sit in the centre. The application layer *owns* the port interfaces it needs, and adapters implement them, so dependencies point inwards. The adapters are package-private, so nothing outside `infrastructure` can even name them; Spring wires them by interface.
- **Alternative:** classic layered architecture (controller → service → DAO). It is simpler, but the service layer then depends on persistence details, and swapping a locking strategy touches business code.

## 2. Repository
- **Status:** ✅ behind ports since P1.3
- **Where:** ports `inventory.application.port.HoldStore` (`createHeld`, `find`) and `ShowStore` (`find`). Adapters `inventory.infrastructure.JdbcHoldStore` and `JdbcShowStore` (plain JDBC, `NamedParameterJdbcTemplate`) map rows to the framework-free aggregates `domain.Hold` and `domain.Show`.
- **Problem:** use cases need to load and save aggregates without knowing SQL, and the domain must not carry persistence annotations (SOLID.md deviation #3).
- **Why:**
  - **Collection-like interface:** one per aggregate, owned by the application layer.
  - **JDBC instead of JPA:** every inventory query needs explicit SQL anyway: `ORDER BY … FOR UPDATE`, `RETURNING`, database-clock expressions (`now() + interval`). JPA added only an entity mirror of each table plus schema validation.
  - **`createHeld(…)` instead of `save(new Hold(…))`:** the database computes `expires_at` from its own clock (SRS A3).
- **Alternative:**
  - **Spring Data JPA (used until P1.2):** less mapping code, but the entity sat in the domain or had to be duplicated. Its `@Modifying` queries can't return rows.
  - **jOOQ:** type-safe SQL, but one more code generator for about six queries.

## 3. Compare-and-Set (conditional update)
- **Status:** ✅ batched in P1.1, lazy expiry in P1.2
- **Where:** `inventory.infrastructure.ConditionalUpdateStrategy.claim` (implements the `SeatLockingStrategy` port), called by `inventory.application.CreateHoldUseCase.execute`:
  `WITH target AS (SELECT id … WHERE show_id=? AND seat_code = ANY(?) AND (status='AVAILABLE' OR (status='HELD' AND hold_expires_at < now())) ORDER BY seat_code FOR UPDATE) UPDATE … FROM target … RETURNING seat_code, price_minor`.
- **Problem:** thousands of concurrent requests for the same seat; exactly one may win, and a multi-seat request must get all of its seats or none.
- **Why:**
  - **Atomic check-and-set:** under READ COMMITTED, Postgres re-checks the `WHERE` on the newest row version after waiting for a lock, so a seat claimed meanwhile is simply not returned.
  - **All-or-nothing:** the use case compares the number of claimed rows with the number requested and throws on a mismatch. The transaction rollback undoes the partial claim and the hold row.
  - **No deadlocks:** `ORDER BY … FOR UPDATE` locks rows in one global order, so two overlapping requests can't each wait for the other.
  - **One round trip:** the claim and the prices come back in one statement instead of N.
  - **Lazy expiry (P1.2):** an expired hold counts as free inside the same compare-and-set, so a late expiry worker can delay cleanup but never availability.
- **Alternative:**
  - **One `UPDATE` per seat (the P0 version):** correct, but N round trips, with lock order depending on Java sorting.
  - **A bare `UPDATE … WHERE seat_code = ANY(?)` without the ordered CTE:** lock order then follows the plan's scan order, which isn't guaranteed.
  - **`NOWAIT` / `SKIP LOCKED`:** faster rejection, but a seat locked by a transaction that later rolls back would be refused wrongly. Kept for the benchmark.
  - **Pessimistic and optimistic strategies:** benchmarked in P1.7–P1.8.


## 4. Data Transfer Object
- **Status:** ✅
- **Where:** `inventory/src/main/java/com/vivek/ticketreservation/inventory/web/CreateHoldRequest.java`, `HoldResponse.java`, `ApiError.java` (HTTP DTOs); `inventory/src/main/java/com/vivek/ticketreservation/inventory/api/HoldCommand.java`, `HoldResult.java` (module-API DTOs, transport-free). All Java records.
- **Problem:** the HTTP contract must not leak JPA entities, and must stay stable when the schema changes.
- **Why:** immutable records with Bean Validation annotations. The contract is defined in `docs/api/*.yaml`.
- **Alternative:** returning entities directly. Less code, but it couples the API to the schema and risks lazy-loading errors.

## 5. Centralised exception translation
- **Status:** ✅
- **Where:** `inventory/src/main/java/com/vivek/ticketreservation/inventory/web/ApiExceptionHandler.java` (`@RestControllerAdvice`).
- **Problem:** every endpoint must map domain failures to the same error shape (`code`, `message`, `retryable`, `timestamp` from P1.3).
- **Why:** one place maps exception → HTTP status → stable error code, and controllers stay free of try/catch.
- **Alternative:** try/catch in each controller. Duplicated, and error codes drift.

## 6. State
- **Status:** ✅ inventory (P1.3); ⬜ P3.2 (`Booking`), P4.2 (`Payment`)
- **Where:**
  - **status enums:** `inventory.domain.{SeatStatus, HoldStatus, ShowStatus}`, each with `canTransitionTo(next)`.
  - **aggregates:** `domain.Hold.{confirm(now), release(), expire(now)}`, `domain.ShowSeat.{hold, book, release}` and `domain.Show.{openSale, closeSale}`. They throw `domain.IllegalStateTransitionException` (or `HoldExpiredException`) on an illegal move.
  - **unit tests:** `inventory/src/test/java/.../domain/` (`StatusTransitionsTest` lists every pair).
  - **database backstop:** the same rules as `CHECK` constraints in `V3__show_seat_state_checks.sql`.
- **Problem:** each aggregate has a lifecycle (see the state machines in plan §4.3–§4.4b). Illegal transitions, such as `CONFIRMED → HELD`, must be impossible.
- **Why:**
  - **One place for the rules:** the transition table lives on the enum, and the aggregate methods add the business conditions: expiry, ownership by hold id, idempotent repeats.
  - **No gap or overlap at expiry:** the rule `expiresAt < now` is identical in Java and SQL, so "claimable by others" and "confirmable" never overlap or leave a gap.
  - **Hot path in SQL:** the hot claim path doesn't load aggregates; it enforces the same rules in one SQL statement (see §3). The aggregates serve confirm/release/expiry and the load-then-update strategies (P1.7).
- **Alternative:**
  - **Full GoF State (one class per state):** heavier. The enum-with-transitions form gives the same guarantee for state machines of 4–7 states.
  - **Scattered `if (status == …)` checks:** rejected.

## 7. Strategy
- **Status:** ✅ P1.7
- **Where:**
  - **Port:** `inventory.application.port.SeatLockingStrategy`, the only type `CreateHoldUseCase` knows.
  - **Implementations:** five package-private classes in `inventory.infrastructure.locking`:
    - `ConditionalUpdateStrategy` (c, default): one ordered `UPDATE … RETURNING`.
    - `PessimisticLockStrategy` (a): `SELECT … ORDER BY … FOR UPDATE`, domain check, `UPDATE`.
    - `OptimisticLockStrategy` (b): read without locks, write `WHERE version = ?`, retry up to 3×.
    - `RedisLockStrategy` (d): `SET NX PX` per seat plus a fencing token, then the database write.
    - `RedisLuaStrategy` (e): an atomic Lua check-and-set in Redis as a filter, decorating (c).
  - **Selection:** `SeatLockingConfig.seatLockingStrategy(…)` is a factory method that builds only the strategy named by `inventory.locking.strategy` (`LockingProperties`). An unknown value fails startup with the allowed values.
  - **Contract tests:** `ticketing-app/.../inventory/SeatLockingContract.java`, one subclass per strategy (`*LockingIT`).
- **Problem:** the benchmark (P1.8) must run *the same* use case against each approach, and production must be able to switch strategy without code changes.
- **Why:**
  - **OCP:** a new strategy is one class plus one `case`; the use case and its tests don't change.
  - **LSP is enforced, not assumed:** all five strategies pass the same contract suite. That covers exactly-one winner, exactly 100 of 100, no partial multi-seat holds, no deadlocks, the expired-seat stampede, and confirm vs release.
  - **Decision logic reused:** the load-then-update strategies use the domain `ShowSeat.isClaimable/hold` rules, so the decision itself lives in one place.
- **Alternative:**
  - **`@ConditionalOnProperty` on five `@Component`s:** this works too, but spreads the choice over five files and allows zero or two beans by mistake.
  - **Branches inside the use case:** untestable in isolation, and violates OCP.

## 8. Adapter
- **Status:** ⬜ P4.1 (payment), P3.3 / P7.8 (inventory)
- **Plan:** port `PaymentGateway` (`createOrder`, `capture`, `refund`, `fetchStatus`), with adapters `MockPaymentGateway` (configurable failure injection) and `SandboxPaymentGateway` (Razorpay test mode). Port `InventoryApi`, with `InProcessInventoryAdapter` (stage 1) and `HttpInventoryAdapter` (stage 2).
- **Why:** core code is identical whether it talks to a fake PSP, a real sandbox or a remote service. Reconciliation (P4.8) needs `fetchStatus` from every gateway.
- **Alternative:** calling the Razorpay SDK directly from use cases. Failure injection becomes impossible and the vendor locks in.

## 9. Facade
- **Status:** 🔨 inventory done: `api/InventoryApi.java` (`hold`, `confirm`, `release`, `get`; P0.14 + P1.4) implemented by package-private `application/InventoryFacade.java`, which delegates to one use case per operation (`CreateHoldUseCase`, `ConfirmHoldUseCase`, `ReleaseHoldUseCase`, `GetHoldUseCase`). `PaymentApi` follows in P4.1. Its only other job is metering (P1.9): it sits outside the use cases' transactions, so the recorded latency includes the commit.
- **Design:** each module exposes one public package, `…inventory.api`, with `InventoryApi` and DTOs. Everything else is package-private or internal, and ArchUnit fails the build if another module imports internals.
- **Why:** a narrow, stable entry point per module is what makes extraction (ADR 002) a mechanical swap.
- **Alternative:** modules calling each other's services and repositories directly. Fast at first, but they become entangled.

## 10. Saga (Orchestrator)
- **Status:** ⬜ P4.4
- **Plan:** `BookingSaga` keeps the persisted step, attempts and `next_retry_at`. Steps: hold → create intent → await capture → confirm hold → confirm booking. Compensations: release hold, cancel intent, refund.
- **Why:** there is no ACID transaction across inventory, booking and the PSP. The orchestrator gives one place to see and resume the money flow.
- **Alternative:** choreography (services react to each other's events). Rejected for the money flow, used for side effects. 2PC/XA was rejected because it blocks and PSPs and Kafka don't support it. Full reasoning: ADR 004 (P4.7).

## 11. Transactional Outbox
- **Status:** ⬜ P5.2–P5.3
- **Plan:** the outbox row is inserted in the same transaction as the state change. `OutboxRelay` polls with `FOR UPDATE SKIP LOCKED` and publishes to Kafka.
- **Why:** it solves the dual-write problem: a DB commit plus a Kafka send can't be atomic, but two inserts in one transaction can.
- **Alternative:** publish after commit (an event is lost on crash), Kafka transactions (they don't cover the Postgres write), Debezium CDC (stretch, P5.10).

## 12. Idempotent Receiver / Idempotency Key
- **Status:** ⬜ P3.4–P3.5 (HTTP), P4.3 (webhooks), P5.5 (Kafka)
- **Plan:** an `idempotency_keys` table with a request hash and the stored response; a `processed_events` insert in the same transaction as the consumer's side effect.
- **Why:** every channel is at-least-once, so duplicates must be harmless (HLD §5.2).
- **Alternative:** a Redis-only key store. Fast, but keys are lost on eviction or failover.

## 13. Template Method
- **Status:** ⬜ P5.5
- **Plan:** the abstract `IdempotentEventConsumer` does dedup → `handle(event)` (abstract) → metrics and error routing to retry topics.
- **Why:** every consumer gets identical dedup and retry behaviour; subclasses only write business logic.
- **Alternative:** copying the dedup code into every consumer, which drifts. Composition with a decorator would also work; Template Method is clearer when the skeleton is fixed.

## 14. Observer / Publish-Subscribe
- **Status:** 🔨 in-process stage ✅ (P2.1): the application port `inventory.application.port.InventoryEventPublisher` publishes framework-free domain events (`inventory.domain.event.HoldExpired`). The adapter `infrastructure.SpringInventoryEventPublisher` uses Spring application events, and observers react with `@TransactionalEventListener` (after commit only), for example `MicrometerHoldMetrics.on(HoldExpired)` and `infrastructure.cache.SeatCacheUpdater.on(SeatsChanged)` (P2.3). The latter hands the work to a bounded executor, so a slow Redis never holds a request's database connection. The durable stage (outbox → Kafka, consumers in other services) is ⬜ P5; it swaps only the adapter.
- **Plan:** modules publish domain events (via the outbox). Notification, ticket, analytics and the WebSocket push subscribe through Kafka consumer groups.
- **Why:** producers don't know or wait for consumers, and new consumers need no producer change (OCP at system level).
- **Alternative:** direct synchronous calls to each side effect. These couple booking latency and availability to email or ticket services.

## 15. Factory
- **Status:** ⬜ P5.1
- **Plan:** `EventEnvelopeFactory.create(aggregate, type, payload)` fills `eventId`, `schemaVersion`, `occurredAt` and `traceId`.
- **Why:** envelope metadata must be identical everywhere; consumers rely on it for dedup and tracing.
- **Alternative:** constructing envelopes by hand at each call site. Fields get forgotten.

## 16. Builder
- **Status:** ⬜ moved to P3.6. P1.6 didn't need it: every contention request is a one-line `HoldCommand`, and a builder would only add indirection. Bookings (P3), with seats, prices, payment and idempotency key, are where test data gets wide enough to justify one.
- **Plan:** test builders such as `ShowFixture.withSeats(100)` and `holdRequest().seats("A1","A2")`.
- **Why:** concurrency tests set up many rows; builders keep tests short and intention-revealing.
- **Alternative:** SQL fixture files. Fine for static data, poor for per-test variations.

## 17. Chain of Responsibility
- **Status:** ⬜ P6.1–P6.5
- **Plan:** gateway filters in order: correlation ID → JWT auth → rate limit → admission-token check → route.
- **Why:** each cross-cutting check is independent and ordered by config, and any link can short-circuit (401, 429).
- **Alternative:** one big filter. Hard to test and reorder.

## 18. Circuit Breaker / Retry / Bulkhead
- **Status:** ⬜ P7.8 (inventory HTTP adapter), P7.9 (payment), plus the PSP adapter in P4.1
- **Plan:** Resilience4j around remote adapters, with timeouts and retry policy from HLD §5.5.
- **Why:** a slow dependency must fail fast instead of exhausting threads (cascading failure).
- **Alternative:** plain timeouts only. They don't stop retry storms against a dependency that is down.

## 19. Token Bucket
- **Status:** ⬜ P6.3
- **Plan:** a Redis Lua script refills tokens and takes one atomically per request, keyed per user and per IP.
- **Why:** it allows short bursts but caps the sustained rate. Atomic in Lua, so it is correct across gateway replicas.
- **Alternative:** fixed window (bursts at window edges), sliding log (memory per request). Compared in ADR 005.

## 20. Fencing token
- **Status:** ✅ P1.7
- **Where:**
  - **Token issue:** `inventory.infrastructure.locking.RedisLockStrategy` gets a token with `INCR fence:{showId}`.
  - **Guarded write:** `UPDATE … SET lock_fence = :token … WHERE id = ANY(:ids) AND lock_fence < :token`.
  - **Column:** `show_seats.lock_fence` (V5).
  - **Re-seeding:** a lost counter is re-seeded from `max(lock_fence)` in Postgres (`nextToken`).
- **Problem:** a Redis lock can expire while its holder is paused (GC, network). The holder then writes believing it still owns the lock.
- **Why:**
  - **Storage checks the token:** the store accepts a write only with a newer token than the last one, so a stale holder's late write is rejected once a newer holder has written (tested: `writeWithAStaleFencingTokenIsRejected`).
  - **Locks released at transaction end:** the locks are freed by a transaction synchronisation after commit or rollback. Otherwise a second client could read the seat before our write is visible.
  - **Counter re-seeding:** without it, losing the Redis counter makes every later write fail. The tests found this.
- **Known limit, documented in the class:** a stale holder can still win if the newer holder hasn't written yet. Locks alone are not a safe source of truth, which is why Postgres stays the authority (ADR 001) and why ADR 003 doesn't choose this strategy.
- **Alternative:**
  - **Redlock across several Redis nodes:** more infrastructure, and still debated as unsafe without fencing.
  - **ZooKeeper/etcd leases with revision numbers:** proper fencing, but a third system on the hot path.

## 21. Read/write separation (CQRS-lite)
- **Status:** ✅ P1.5; Redis read model ✅ P2.3 (`infrastructure.cache.CachedCatalogReadStore` + `SeatStateCache`, fed by `SeatsChanged` events)
- **Where:**
  - **Published read interfaces:** `inventory.api.CatalogQuery` and `SeatMapQuery`, implemented by `application.CatalogQueries` (`@Transactional(readOnly = true)`).
  - **Read port:** `application.port.CatalogReadStore`, which returns ready-made views and no aggregates.
  - **Adapter:** `infrastructure.JdbcCatalogReadStore`.
  - **HTTP:** `web.CatalogController`.
- **Problem:** seat-map reads outnumber writes by orders of magnitude during a sale. They must be cacheable and allowed to lag slightly, while the hold path must stay strictly consistent.
- **Why:**
  - **Separate paths from the start:** reads go through their own interfaces and port, share no model with the write side, and return the contract's view records directly.
  - **Lazy expiry in reads too:** the read model applies the same rule as the claim (`HELD` + expired = `AVAILABLE`), so it never shows a seat as taken when a hold for it would succeed.
  - **Swappable read store:** P2.3 can replace `CatalogReadStore` with Redis without touching holds.
- **Alternative:**
  - **Full CQRS with a separate read database fed by events:** too much machinery until load tests show Postgres reads are the bottleneck. The event feed arrives with the outbox in P5.
  - **Reusing the write aggregates for reads:** loads more than the view needs and couples cache decisions to the domain.

## 22. Decorator
- **Status:** ✅ P1.7
- **Where:** `inventory.infrastructure.locking.RedisLuaStrategy` implements `SeatLockingStrategy` and wraps another `SeatLockingStrategy`, the `ConditionalUpdateStrategy`. `SeatLockingConfig` composes them.
- **Second use (P2.3):** `infrastructure.cache.CachedCatalogReadStore` implements `CatalogReadStore` and wraps the JDBC store. It answers seat maps from Redis, and delegates everything else (and every Redis failure) to the database. `SeatCacheConfig` makes it `@Primary`, so `CatalogQueries` gets the cached version without knowing it.
- **Problem:** the Redis filter adds behaviour *in front of* the authoritative claim: reject early, set marks, and undo them on rollback. It must not duplicate or change the claim itself.
- **Why:**
  - **Same interface:** the decorator has the same interface, so the use case can't tell the difference.
  - **Composable:** the filter can wrap any authority strategy, which is how P2.4 will use it.
  - **Safe by construction:** the database decision stays in one class, so the filter can only ever *reject* more, never *accept* more. False rejects are possible; oversells are not.
- **Alternative:**
  - **Copying the conditional-update SQL into the Redis class:** two copies of the critical query.
  - **Inheritance (`RedisLuaStrategy extends ConditionalUpdateStrategy`):** this ties the filter to one authority.

## 23. Competing consumers (SKIP LOCKED work queue)
- **Status:** ✅ P2.1–P2.2
- **Where:**
  - **The job:** `inventory.application.ExpireHoldsJob.runBatch`, which uses `JdbcHoldStore.lockExpired`: `SELECT … WHERE status = 'HELD' AND expires_at < now() ORDER BY expires_at LIMIT n FOR UPDATE SKIP LOCKED`.
  - **Who runs it:** every replica, through `infrastructure.ScheduledHoldExpiry`.
  - **Proof:** `ticketing-app/.../inventory/ExpiryWorkersIT.java`.
- **Problem:** expired holds must be cleaned up by *some* replica. Two replicas must never expire the same hold twice, which would mean double events and a double "booking expired", and one slow replica must not block the others.
- **Why:**
  - **The table is the queue:** each worker locks a different slice of due rows, and rows locked by another worker are skipped instead of waited for.
  - **No leader needed:** no lock table, no leader to elect or fail over. More replicas means more cleanup capacity.
  - **Crash-safe:** if a worker dies mid-batch, its transaction rolls back and the rows are simply picked up next tick.
  - **Measured:** 8 workers, 2,000 holds, each expired exactly once, with work split 550/500/400/300/200/50/0/0 and no event duplicated.
- **Alternative:**
  - **ShedLock (one replica runs the job at a time, via a lock row):** a correct, popular choice when a job *can't* be partitioned. Here it would only remove parallelism, and adds a lock table plus TTL tuning (a lock held past a crash delays cleanup until it expires).
  - **Leader election (Kubernetes Lease, ZooKeeper):** same single-runner result, with more infrastructure.
  - **A message queue of expiry timers:** precise, but a second source of truth about holds.

## 24. Versioned last-writer-wins (order-independent cache updates)
- **Status:** ✅ P2.3
- **Where:**
  - **Source of versions:** every seat write returns the row's `version` (`RETURNING … version`).
  - **Event:** `domain.event.SeatsChanged` carries those versions.
  - **Guarded write:** `infrastructure.cache.SeatStateCache.APPLY` (Lua) keeps an entry only if the incoming version is higher.
  - **Warm-up:** uses the same script, then sets a `:ready` marker.
- **Problem:**
  - **Out of order:** cache updates come from asynchronous events, which can arrive late, twice, or swapped (a release processed after the re-hold that followed it).
  - **Warm-up races:** a warm-up snapshot can be older than an event that was already applied.
- **Why:**
  - **Monotonic versions:** the database row version only ever increases, so "highest version wins" makes the cache converge to the newest state whatever the delivery order.
  - **Atomic:** the comparison runs inside Redis (one script), so concurrent updaters can't interleave between check and write.
  - **No half-filled reads:** the ready marker means readers never see a partly warmed hash.
- **Alternative:**
  - **Overwrite blindly:** the last *delivered* wins, not the last *written*, so stale data appears under reordering.
  - **Single-threaded ordered delivery:** a head-of-line blocking risk, and still wrong if events are lost and replayed.
  - **Delete-on-change and re-read from Postgres:** simple, but every change turns into a database read during the hottest seconds.

## 25. Null Object
- **Status:** ✅ P2.4
- **Where:** `inventory.infrastructure.NoSeatFilter.passThroughSeatFilter()`, a `SeatFilter` that always answers `SKIPPED`. It's always defined; the Redis filter is `@Primary` when enabled.
- **Problem:** the hold flow must work identically with the fast path off (no Redis, or `FAST_PATH_ENABLED=false`), without `if (filter != null)` checks in the use case.
- **Why:** callers keep one code path. Turning the fast path off is a configuration change, and nothing can forget a null check.
- **Alternative:** an `Optional<SeatFilter>` or a nullable field, which spreads conditionals. `@ConditionalOnMissingBean` between ordinary configuration classes is unreliable (it depends on registration order), so `@Primary` is used instead.

## 26. Fast-reject filter (cache as filter, DB as authority)
- **Status:** ✅ P2.4 (fail-open hardening and a breaker in P2.6)
- **Where:**
  - **Wrapper:** `inventory.application.FilteredCreateHold` wraps `CreateHoldUseCase` and runs outside its transaction.
  - **Port:** `application.port.SeatFilter` (`REJECTED` / `RESERVED` / `SKIPPED`).
  - **Redis implementation:** `infrastructure.cache.RedisSeatFilter`, with the scripts `RESERVE` (check all seats, then mark pending) and `CANCEL`.
- **Problem:** in a flash sale almost every request loses. Sending each loser through a database transaction, which waits on the winner's row lock, is what limits throughput (P1.8 hot-seat).
- **Why:**
  - **Losers turned away in Redis:** a cheap atomic check in Redis rejects most of them in about a millisecond. The pending mark means only the *first* of 1,000 identical requests reaches Postgres (tested: more than 900 rejected in Redis).
  - **One-sided error:** the filter can only reject; acceptance is always decided by the conditional update in Postgres. A stale cache can cause a false "unavailable", never an oversell.
  - **No pool exhaustion:** it runs before a connection is taken, so a slow Redis can't exhaust the pool (ADR 003), and any doubt (cold cache, unknown seat, Redis error) means `SKIPPED`.
- **Alternative:**
  - **Redis Lua inside the transaction (P1.7 `RedisLuaStrategy`):** the same rejection power, but it took the whole app down when Redis hung (P1.8).
  - **Bloom filter of sold seats:** no per-hold expiry, and it can't express "pending".
  - **Waiting room only (P6):** limits how many users get in, but doesn't stop admitted users stampeding one seat. The two are complementary.

## 27. Reconciliation (anti-entropy)
- **Status:** ✅ P2.5 (the payment reconciliation job follows in P4.8)
- **Where:**
  - **The job:** `inventory.infrastructure.cache.SeatCacheReconciler.reconcile(showId)`, scheduled by `ScheduledSeatCacheReconciliation` every `inventory.reconcile.interval` (30 s).
  - **Repair script:** `COMPARE_AND_SET` (Lua) for diverged entries; `SeatStateCache.APPLY` for stale and missing ones.
- **Problem:** the cache is fed by best-effort events: in-process until the outbox, dropped when the update queue is full, and lost on a crash or a Redis restart. Without a repair loop, one lost event would leave a seat wrong until its next change, which for a booked seat is never.
- **Why:**
  - **Bounded drift:** periodically comparing with the authority and repairing caps the drift at one interval, whatever went wrong.
  - **Repairs never fight live traffic:** version-guarded writes for stale entries, and compare-and-set for same-version divergence. Entries ahead of the snapshot (an event landed after our read) and fresh pending marks are recognised as in-flight work.
  - **Idempotent and read-only on Postgres:** every replica may run it without coordination (see #23).
- **Alternative:**
  - **TTL on cache entries:** bounds staleness too, but forces regular full reloads during the hottest minutes and still serves wrong data until expiry.
  - **Rebuild the whole hash periodically:** simpler, but it overwrites newer events with an older snapshot. That is exactly the race the version rules avoid.
  - **Merkle-tree comparison:** this is for huge datasets across nodes; a show has at most a few thousand seats, so a direct comparison is cheap.
